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
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Telsiz servisi.
 * - Aynı WiFi ağında UDP broadcast ile diğer cihazı otomatik bulur (keşif).
 * - Bulunan cihaza ham PCM ses paketleri gönderir / ondan gelenleri çalar.
 * - Foreground Service olduğu için uygulama kapansa/arka plana atılsa bile çalışmaya devam eder.
 * - Ekranın üstünde kayan bir "KONUŞ" butonu gösterir, böylece uygulamayı açmadan da
 *   basılı tutarak konuşabilirsin (overlay izni verilmişse).
 */
class WalkieTalkieService : Service() {

    companion object {
        const val DISCOVERY_PORT = 8888
        const val AUDIO_PORT = 8889
        const val SAMPLE_RATE = 16000
        const val CHANNEL_ID = "walkie_channel"
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): WalkieTalkieService = this@WalkieTalkieService
    }
    override fun onBind(intent: Intent?): IBinder = binder

    private val myId = UUID.randomUUID().toString()
    @Volatile private var running = false
    @Volatile private var transmitting = false

    private var discoverySocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    @Volatile private var peerAddress: InetAddress? = null

    private var audioTrack: AudioTrack? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null

    // Aktivite bu callback ile durum mesajlarını (bağlandı / aranıyor) alır
    var onStatusChanged: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForegroundNotification()
        acquireMulticastLock()
        startDiscoveryLoop()
        startAudioReceiver()
        showFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        transmitting = false
        try { discoverySocket?.close() } catch (e: Exception) {}
        try { audioSocket?.close() } catch (e: Exception) {}
        try { audioTrack?.stop() } catch (e: Exception) {}
        try { audioTrack?.release() } catch (e: Exception) {}
        try { multicastLock?.release() } catch (e: Exception) {}
        removeFloatingButton()
        super.onDestroy()
    }

    // ---------- Bildirim (Foreground Service için zorunlu) ----------

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Telsiz", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telsiz aktif")
            .setContentText("Aynı ağdaki cihaz aranıyor / dinleniyor...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    // ---------- WiFi broadcast paketlerini almak için gerekli kilit ----------

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

    // ---------- Keşif: diğer cihazı bulma ----------

    private fun startDiscoveryLoop() {
        thread {
            try {
                discoverySocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                }

                // Periyodik olarak "buradayım" yayını yap
                thread {
                    while (running) {
                        try {
                            val msg = "WALKIE_HELLO:$myId".toByteArray()
                            val addr = getBroadcastAddress()
                            discoverySocket?.send(DatagramPacket(msg, msg.size, addr, DISCOVERY_PORT))
                        } catch (e: Exception) { /* yok say, tekrar denenecek */ }
                        Thread.sleep(2000)
                    }
                }

                // Gelen yayınları dinle
                val buf = ByteArray(256)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    discoverySocket?.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    if (text.startsWith("WALKIE_HELLO:") && !text.endsWith(myId)) {
                        if (peerAddress?.hostAddress != packet.address.hostAddress) {
                            peerAddress = packet.address
                            onStatusChanged?.invoke("Bağlandı: ${packet.address.hostAddress}")
                        }
                    }
                }
            } catch (e: Exception) {
                onStatusChanged?.invoke("Keşif hatası: ${e.message}")
            }
        }
    }

    // ---------- Ses alma / çalma (her zaman açık, arka planda da) ----------

    private fun startAudioReceiver() {
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
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    audioSocket?.receive(packet)
                    audioTrack?.write(packet.data, 0, packet.length)
                }
            } catch (e: Exception) {
                onStatusChanged?.invoke("Ses alma hatası: ${e.message}")
            }
        }
    }

    // ---------- Ses gönderme (sadece PTT butonuna basılıyken) ----------

    fun startTransmitting() {
        if (transmitting) return
        val peer = peerAddress
        if (peer == null) {
            onStatusChanged?.invoke("Henüz karşı cihaz bulunamadı, bekleniyor...")
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
                        sendSocket.send(DatagramPacket(buf, read, peer, AUDIO_PORT))
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

    fun isTransmitting(): Boolean = transmitting
    fun hasPeer(): Boolean = peerAddress != null

    // ---------- Ekranın üstünde kayan PTT butonu (uygulama kapalıyken de kullanılabilir) ----------

    private fun showFloatingButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return // izin verilmemiş, sorun değil: uygulama içi buton yine çalışır
        }
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
        } catch (e: Exception) {
            // overlay eklenemedi (izin sorunu vb.), sessizce geç
        }
    }

    private fun removeFloatingButton() {
        try {
            floatingButton?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {}
    }
}
