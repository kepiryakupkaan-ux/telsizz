package com.example.walkietalkie

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var service: WalkieTalkieService? = null
    private var bound = false

    private lateinit var statusText: TextView
    private lateinit var pttButton: Button
    private lateinit var listenButton: Button
    private lateinit var deviceListView: ListView

    private var currentPeers: List<PeerInfo> = emptyList()
    private lateinit var listAdapter: ArrayAdapter<String>

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as WalkieTalkieService.LocalBinder
            service = b.getService()
            bound = true
            service?.onStatusChanged = { status ->
                runOnUiThread { statusText.text = status }
            }
            service?.onPeersChanged = { peers ->
                runOnUiThread { updatePeerList(peers) }
            }
            runOnUiThread { updatePeerList(service?.getCurrentPeers() ?: emptyList()) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        pttButton = findViewById(R.id.pttButton)
        listenButton = findViewById(R.id.listenButton)
        deviceListView = findViewById(R.id.deviceListView)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, mutableListOf())
        deviceListView.adapter = listAdapter
        deviceListView.choiceMode = ListView.CHOICE_MODE_SINGLE

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            if (position < currentPeers.size) {
                val peer = currentPeers[position]
                service?.selectPeer(peer.id)
            }
        }

        // BAS KONUŞ: basılı tuttuğun sürece senin sesin zorla karşıya gider
        pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    service?.startTransmitting()
                    pttButton.text = "KONUŞUYOR..."
                    true
                }
                MotionEvent.ACTION_UP -> {
                    service?.stopTransmitting()
                    pttButton.text = "BASILI TUT VE KONUŞ"
                    true
                }
                else -> false
            }
        }

        // BAS DİNLE: basılı tuttuğun sürece karşı tarafın mikrofonu zorla senin için açılır
        listenButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    service?.startRequestingAudio()
                    listenButton.text = "DİNLENİYOR..."
                    true
                }
                MotionEvent.ACTION_UP -> {
                    service?.stopRequestingAudio()
                    listenButton.text = "BASILI TUT VE DİNLE"
                    true
                }
                else -> false
            }
        }

        ensureDeviceName {
            requestNeededPermissions()
        }
    }

    private fun updatePeerList(peers: List<PeerInfo>) {
        currentPeers = peers
        listAdapter.clear()
        listAdapter.addAll(peers.map { it.name })
        listAdapter.notifyDataSetChanged()
        if (peers.isEmpty()) {
            statusText.text = "Aynı WiFi'deki cihazlar aranıyor..."
        }
    }

    // ---------- Cihaz adı: ilk açılışta bir kere sorulur ----------

    private fun ensureDeviceName(onDone: () -> Unit) {
        val prefs = getSharedPreferences("walkie_prefs", Context.MODE_PRIVATE)
        val existing = prefs.getString("device_name", null)
        if (!existing.isNullOrBlank()) {
            onDone()
            return
        }
        val input = EditText(this).apply {
            hint = "Örn: Ahmetin Telefonu"
        }
        AlertDialog.Builder(this)
            .setTitle("Cihazına bir isim ver")
            .setMessage("Bu isim, diğer cihazların listesinde görünecek.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Kaydet") { _, _ ->
                val name = input.text.toString().trim().ifBlank { Build.MODEL ?: "Cihaz" }
                prefs.edit().putString("device_name", name).apply()
                onDone()
            }
            .show()
    }

    // ---------- İzinler ----------

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }

        startAndBindService()
    }

    private fun startAndBindService() {
        val intent = Intent(this, WalkieTalkieService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        // Güvenlik: uygulamadan çıkarken DİNLE isteği açık kalmış olabilir, kapat.
        service?.stopRequestingAudio()
        super.onStop()
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
        // Not: servis burada durdurulmuyor -> KONUŞ (kayan buton) ve uzaktan DİNLE isteğine
        // yanıt verme, uygulama kapansa bile arka planda çalışmaya devam eder.
    }
}
