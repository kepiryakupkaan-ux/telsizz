package com.example.walkietalkie

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Ağda bulunan bir cihazı temsil eder. */
data class PeerInfo(
    val id: String,
    val name: String,
    val address: InetAddress,
    var lastSeen: Long
)

private data class RequesterInfo(val address: InetAddress, var lastSeen: Long)

/**
 * Telsiz servisi.
 *
 * İKİ BAĞIMSIZ MEKANİZMA:
 *
 * 1) BAS KONUŞ (manuel, zorla gönderme): Kullanıcı butona bastığı sürece kendi sesini
 *    seçili cihaza gönderir. Karşı taraf hiçbir şey yapmasa da, uygulaması kapalı olsa da,
 *    kayan buton sayesinde bu her zaman kullanılabilir.
 *
 * 2) BAS DİNLE (uzaktan tetikleme, zorla alma): Kullanıcı butona bastığı sürece seçili
 *    cihaza küçük bir "LISTEN_REQ" sinyali gönderir. Bu sinyali alan cihaz, kendi
 *    kullanıcısı hiçbir şey yapmasa bile (uygulaması kapalı/arka planda olsa bile)
 *    otomatik olarak mikrofonunu açıp isteği yapan cihaza ses akıtmaya başlar.
 *    Buton bırakılınca sinyal kesilir, birkaç saniye içinde karşı taraf da durur.
 */
class WalkieTalkieService : Service() {

    companion object {
        const val DISCOVERY_PORT = 8888
        const val AUDIO_PORT = 8889
        const val SAMPLE_RATE = 16000
        const val CHANNEL_ID = "walkie_channel"
        const val PEER_TIMEOUT_MS = 8000L
        const val LISTEN_REQ_INTERVAL_MS = 500L
        const val LISTEN_REQ_EXPIRE_MS = 1500L
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private val myId = UUID.randomUUID().toString()
    private var myName: String = "Cihaz"

    @Volatile private var running = false

    // --- BAS KONUŞ (manuel gönderme) durumu ---
    @Volatile private var manualTransmitting = false
    private var manualSendThread: Thread? = null

    // --- BAS DİNLE (uzaktan istek gönderme) durumu ---
    @Volatile private var requestingListenFrom = false
    private var listenRequestThread: Thread? = null

    // --- Karşıdan bize gelen "beni dinlet" istekleri (bizim mikrofonumuzu isteyenler) ---
    private val remoteListenRequesters = ConcurrentHashMap<String, RequesterInfo>()
    @Volatile private var remoteBroadcasting = false
    private var remoteBroadcastThread: Thread? = null

    // --- Ses alma (kendi hoparlörümüzden çalma) ---
    @Volatile private var listening = false
    private var audioSocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null

    private var discoverySocket: DatagramSocket? = null
    private val peers = ConcurrentHashMap<String, PeerInfo>()
    @Volatile private var selectedPeerId: String? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    var onStatusChanged: ((String) -> Unit)? = null
    var onPeersChanged: ((List<PeerInfo>) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        myName = getSharedPreferences("walkie_prefs", Context.MODE_PRIVATE)
            .getString("device_name", null) ?: Build.MODEL ?: "Cihaz"
        running = true
        startForegroundNotification()
        acquireMulticastLock()
        startDiscoveryLoop()
        startPeerCleanupLoop()
        startRemoteRequesterCleanupLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        manualTransmitting = false
        requestingListenFrom = false
        remoteBroadcasting = false
        listening = false
        try { discoverySocket?.close() } catch (e: Exception) {}
        try { audioSocket?.close() } catch (e: Exception) {}
        try { audioTrack?.stop() } catch (e: Exception) {}
        try { audioTrack?.release() } catch (e: Exception) {}
        try { multicastLock?.release() } catch (e: Exception) {}
        super.onDestroy()
    }

    // ---------- Bildirim ----------

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Telsiz", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telsiz aktif")
            .setContentText("Adım: $myName — cihazlar aranıyor...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("walkie_multicast_lock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun getBroadcastAddress(): InetAddress {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo
        val broadcastInt = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val quads = ByteArray(4)
        for (k in 0..3) quads[k] = ((broadcastInt shr (k * 8)) and 0xFF).toByte()
        return InetAddress.getByAddress(quads)
    }

    // ---------- Keşif + kontrol mesajları (aynı soket üzerinden) ----------

    private fun startDiscoveryLoop() {
        thread {
            try {
                discoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }

                // Periyodik "buradayım" yayını
                thread {
                    while (running) {
                        try {
                            val safeName = myName.replace(":", " ")
                            val msg = "WALKIE_HELLO:$myId:$safeName".toByteArray()
                            val addr = getBroadcastAddress()
                            discoverySocket?.send(DatagramPacket(msg, msg.size, addr, DISCOVERY_PORT))
                        } catch (e: Exception) { }
                        Thread.sleep(2000)
                    }
                }

                val buf = ByteArray(256)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    discoverySocket?.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val parts = text.split(":", limit = 3)

                    when {
                        parts.size == 3 && parts[0] == "WALKIE_HELLO" && parts[1] != myId -> {
                            val id = parts[1]; val name = parts[2]
                            peers[id] = PeerInfo(id, name, packet.address, System.currentTimeMillis())
                            onPeersChanged?.invoke(peers.values.sortedBy { it.name })
                        }
                        parts.size >= 2 && parts[0] == "LISTEN_REQ" && parts[1] != myId -> {
                            val requesterId = parts[1]
                            remoteListenRequesters[requesterId] = RequesterInfo(packet.address, System.currentTimeMillis())
                            ensureRemoteBroadcastRunning()
                        }
                    }
                }
            } catch (e: Exception) {
                onStatusChanged?.invoke("Keşif hatası: ${e.message}")
            }
        }
    }

    private fun startPeerCleanupLoop() {
        thread {
            while (running) {
                Thread.sleep(3000)
                val now = System.currentTimeMillis()
                val before = peers.size
                peers.entries.removeIf { now - it.value.lastSeen > PEER_TIMEOUT_MS }
                if (peers.size != before) {
                    onPeersChanged?.invoke(peers.values.sortedBy { it.name })
                    if (selectedPeerId != null && !peers.containsKey(selectedPeerId)) {
                        selectedPeerId = null
                        onStatusChanged?.invoke("Seçili cihaz bağlantıyı kaybetti")
                    }
                }
            }
        }
    }

    /** Bizden ses isteyip de artık istek göndermeyi bırakanları listeden temizler (zaman aşımı). */
    private fun startRemoteRequesterCleanupLoop() {
        thread {
            while (running) {
                Thread.sleep(400)
                val now = System.currentTimeMillis()
                remoteListenRequesters.entries.removeIf { now - it.value.lastSeen > LISTEN_REQ_EXPIRE_MS }
                if (remoteListenRequesters.isEmpty()) {
                    remoteBroadcasting = false
                }
            }
        }
    }

    fun selectPeer(id: String) {
        selectedPeerId = id
        val p = peers[id]
        onStatusChanged?.invoke(if (p != null) "Seçili: ${p.name}" else "Cihaz bulunamadı")
    }

    fun getSelectedPeerId(): String? = selectedPeerId
    fun getCurrentPeers(): List<PeerInfo> = peers.values.sortedBy { it.name }
    fun getMyName(): String = myName

    // ==================== BAS KONUŞ: manuel, zorla gönderme ====================

    fun startTransmitting() {
        if (manualTransmitting) return
        val peer = selectedPeerId?.let { peers[it] }
        if (peer == null) {
            onStatusChanged?.invoke("Önce bir cihaz seç")
            return
        }
        manualTransmitting = true
        manualSendThread = thread {
            sendMicTo(peer.address) { manualTransmitting }
        }
    }

    fun stopTransmitting() {
        manualTransmitting = false
    }

    // ==================== BAS DİNLE: uzaktan istek gönder + gelen sesi çal ====================

    /** Kullanıcı BAS DİNLE'ye bastığında çağrılır — SADECE sinyal gönderir, çalma işini etkilemez. */
    fun startRequestingAudio() {
        val peer = selectedPeerId?.let { peers[it] }
        if (peer == null) {
            onStatusChanged?.invoke("Önce bir cihaz seç")
            return
        }
        if (requestingListenFrom) return
        requestingListenFrom = true
        listenRequestThread = thread {
            while (requestingListenFrom) {
                try {
                    val msg = "LISTEN_REQ:$myId".toByteArray()
                    discoverySocket?.send(DatagramPacket(msg, msg.size, peer.address, DISCOVERY_PORT))
                } catch (e: Exception) { }
                Thread.sleep(LISTEN_REQ_INTERVAL_MS)
            }
        }
    }

    /** Kullanıcı BAS DİNLE'yi bıraktığında çağrılır. */
    fun stopRequestingAudio() {
        requestingListenFrom = false
    }

    /** Uygulama görünür olduğunda çağrılır (MainActivity onStart/bağlanınca) — gelen sesi HER ZAMAN çalar. */
    fun startListening() {
        if (listening) return
        listening = true
        thread {
            try {
                audioSocket = DatagramSocket(AUDIO_PORT)
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                audioTrack = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf,
                    AudioTrack.MODE_STREAM
                )
                audioTrack?.play()

                val buf = ByteArray(2048)
                while (listening) {
                    val packet = DatagramPacket(buf, buf.size)
                    audioSocket?.receive(packet)
                    audioTrack?.write(packet.data, 0, packet.length)
                }
            } catch (e: Exception) {
                // soket kapanırken oluşan hata normal, yok say
            } finally {
                try { audioSocket?.close() } catch (e: Exception) {}
                try { audioTrack?.stop() } catch (e: Exception) {}
                try { audioTrack?.release() } catch (e: Exception) {}
                audioTrack = null
                audioSocket = null
            }
        }
    }

    /** Uygulama arka plana atıldığında çağrılır (MainActivity onStop). */
    fun stopListening() {
        listening = false
        try { audioSocket?.close() } catch (e: Exception) {}
    }

    // ==================== Karşıdan "LISTEN_REQ" gelince: mikrofonu otomatik aç ====================

    private fun ensureRemoteBroadcastRunning() {
        if (remoteBroadcasting) return
        remoteBroadcasting = true
        remoteBroadcastThread = thread {
            var recorder: AudioRecord? = null
            var sendSocket: DatagramSocket? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                sendSocket = DatagramSocket()
                val buf = ByteArray(2048)
                recorder.startRecording()
                while (remoteBroadcasting && remoteListenRequesters.isNotEmpty()) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        // o an bizi isteyen herkese gönder
                        for (requester in remoteListenRequesters.values) {
                            try {
                                sendSocket.send(DatagramPacket(buf, read, requester.address, AUDIO_PORT))
                            } catch (e: Exception) { }
                        }
                    }
                }
            } catch (e: Exception) {
                onStatusChanged?.invoke("Uzaktan yayın hatası: ${e.message}")
            } finally {
                try { recorder?.stop() } catch (e: Exception) {}
                try { recorder?.release() } catch (e: Exception) {}
                try { sendSocket?.close() } catch (e: Exception) {}
                remoteBroadcasting = false
            }
        }
    }

    // ==================== Ortak: mikrofonu belirli bir adrese sürekli gönder ====================

    private fun sendMicTo(target: InetAddress, keepRunning: () -> Boolean) {
        var recorder: AudioRecord? = null
        var sendSocket: DatagramSocket? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
            sendSocket = DatagramSocket()
            val buf = ByteArray(2048)
            recorder.startRecording()
            while (keepRunning()) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    sendSocket.send(DatagramPacket(buf, read, target, AUDIO_PORT))
                }
            }
        } catch (e: Exception) {
            onStatusChanged?.invoke("Gönderim hatası: ${e.message}")
        } finally {
            try { recorder?.stop() } catch (e: Exception) {}
            try { recorder?.release() } catch (e: Exception) {}
            try { sendSocket?.close() } catch (e: Exception) {}
        }
    }

}
