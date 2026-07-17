package com.example.walkietalkie

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
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

/**
 * Telsiz servisi.
 * - Aynı WiFi ağındaki tüm cihazları UDP broadcast ile keşfeder (liste halinde).
 * - Kullanıcının seçtiği cihaza ses gönderir (bas-konuş) — gönderme her zaman, uygulama
 *   kapalı olsa bile çalışır (foreground service + kayan buton sayesinde).
 * - Ses ALMA (dinleme) ise dışarıdan start/stopListening() ile açılıp kapatılır;
 *   bunu MainActivity, uygulama görünür olduğunda açık, olmadığında kapalı tutacak şekilde çağırır.
 */
class WalkieTalkieService : Service() {

    companion object {
        const val DISCOVERY_PORT = 8888
        const val AUDIO_PORT = 8889
        const val SAMPLE_RATE = 16000
        const val CHANNEL_ID = "walkie_channel"
        const val PEER_TIMEOUT_MS = 8000L
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private val myId = UUID.randomUUID().toString()
    private var myName: String = "Cihaz"

    @Volatile private var running = false
    @Volatile private var transmitting = false
    @Volatile private var listening = false

    private var discoverySocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null

    private val peers = ConcurrentHashMap<String, PeerInfo>()
    @Volatile private var selectedPeerId: String? = null

    private var multicastLock: WifiManager.MulticastLock? = null
    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null

    // UI callback'leri
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
        showFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        transmitting = false
        listening = false
        try { discoverySocket?.close() } catch (e: Exception) {}
        try { audioSocket?.close() } catch (e: Exception) {}
        try { audioTrack?.stop() } catch (e: Exception) {}
        try { audioTrack?.release() } catch (e: Exception) {}
        try { multicastLock?.release() } catch (e: Exception) {}
        removeFloatingButton()
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

    // ---------- Keşif: ağdaki TÜM cihazları bul, listele ----------

    private fun startDiscoveryLoop() {
        thread {
            try {
                discoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                }

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
                    if (parts.size == 3 && parts[0] == "WALKIE_HELLO" && parts[1] != myId) {
                        val id = parts[1]
                        val name = parts[2]
                        peers[id] = PeerInfo(id, name, packet.address, System.currentTimeMillis())
                        onPeersChanged?.invoke(peers.values.sortedBy { it.name })
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

    fun selectPeer(id: String) {
        selectedPeerId = id
        val p = peers[id]
        onStatusChanged?.invoke(if (p != null) "Seçili: ${p.name}" else "Cihaz bulunamadı")
    }

    fun getSelectedPeerId(): String? = selectedPeerId
    fun getCurrentPeers(): List<PeerInfo> = peers.values.sortedBy { it.name }
    fun getMyName(): String = myName

    // ---------- Ses ALMA (dinleme) — sadece uygulama görünürken açık ----------

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
                    // sadece seçili cihazdan gelen sesi çal
                    val selected = selectedPeerId?.let { peers[it] }
                    if (selected != null && packet.address.hostAddress == selected.address.hostAddress) {
                        audioTrack?.write(packet.data, 0, packet.length)
                    }
                }
            } catch (e: Exception) {
                // dinleme kapatılırken soket kapanır, hata normal, yok say
            } finally {
                try { audioSocket?.close() } catch (e: Exception) {}
                try { audioTrack?.stop() } catch (e: Exception) {}
                try { audioTrack?.release() } catch (e: Exception) {}
                audioTrack = null
                audioSocket = null
            }
        }
    }

    fun stopListening() {
        listening = false
        try { audioSocket?.close() } catch (e: Exception) {}
    }

    // ---------- Ses GÖNDERME (PTT) — her zaman çalışır, uygulama kapalıyken de ----------

    fun startTransmitting() {
        if (transmitting) return
        val peer = selectedPeerId?.let { peers[it] }
        if (peer == null) {
            onStatusChanged?.invoke("Önce bir cihaz seç")
            return
        }
        transmitting = true
        thread {
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
                while (transmitting) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        sendSocket.send(DatagramPacket(buf, read, peer.address, AUDIO_PORT))
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

    fun stopTransmitting() {
        transmitting = false
    }

    // ---------- Kayan PTT butonu ----------

    private fun showFloatingButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val button = Button(this).apply {
            text = "KONUŞ"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            alpha = 0.9f
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300

        var lastTouchX = 0f
        var lastTouchY = 0f
        var initialX = 0
        var initialY = 0

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    startTransmitting()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - lastTouchX).toInt()
                    params.y = initialY + (event.rawY - lastTouchY).toInt()
                    windowManager?.updateViewLayout(button, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopTransmitting()
                    true
                }
                else -> false
            }
        }

        floatingButton = button
        try {
            windowManager?.addView(button, params)
        } catch (e: Exception) { }
    }

    private fun removeFloatingButton() {
        try {
            floatingButton?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {}
    }
}
