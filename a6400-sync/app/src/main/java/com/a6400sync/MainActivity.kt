package com.a6400sync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = TextView(this).apply { textSize = 15f; setPadding(0, 32, 0, 0) }
        val help = TextView(this).apply {
            text = "1. Na câmera: Menu > Rede > Ctrl c/ Smartphone > Ligado\n" +
                "2. Conecte o Wi-Fi do celular na rede DIRECT-xxxx:ILCE-6400\n" +
                "3. Toque em Iniciar e fotografe normalmente."
        }
        val start = Button(this).apply {
            text = "Iniciar sincronização"
            setOnClickListener {
                val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) perms += listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.NEARBY_WIFI_DEVICES)
                requestPermissions(perms.toTypedArray(), 1)
                startForegroundService(Intent(this@MainActivity, SyncService::class.java))
            }
        }
        val stop = Button(this).apply {
            text = "Parar"
            setOnClickListener { stopService(Intent(this@MainActivity, SyncService::class.java)) }
        }
        SyncService.onStatus = { msg -> runOnUiThread { log.text = "$msg\n${log.text}".take(4000) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48)
            addView(help); addView(start); addView(stop); addView(log)
        })
    }
}
