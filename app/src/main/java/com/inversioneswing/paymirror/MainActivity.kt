package com.inversioneswing.paymirror

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Typeface

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // DISEÑO OMEGA ULTIMATE (v5.5)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF121212.toInt())
        }

        val title = TextView(this).apply {
            text = "WING SENTINEL v5.5 OMEGA"
            textSize = 24f
            setTextColor(0xFF00FFCC.toInt()) // Cyan Stark
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 40)
            gravity = Gravity.CENTER
        }

        val btnAutoStart = Button(this).apply {
            text = "CONFIGURAR INICIO AUTOMÁTICO (HUAWEI)"
            setBackgroundColor(0xFF005577.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { openAutoStartSettings() }
        }

        val btnSync = Button(this).apply {
            text = "REINICIAR OÍDO DE JARVIS"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { 
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        layout.addView(title)
        layout.addView(btnAutoStart)
        val space1 = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) }
        layout.addView(space1)
        layout.addView(btnSync)
        setContentView(layout)

        checkInitialSystems()
        iniciarPulsoResurreccion()
    }

    private fun openAutoStartSettings() {
        val intent = Intent()
        try {
            intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            startActivity(intent)
        } catch (e: Exception) {
            try {
                intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Busque 'Inicio de aplicaciones' en Ajustes", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun iniciarPulsoResurreccion() {
        val intent = Intent(this, StarkResurrector::class.java)
        sendBroadcast(intent)
    }

    private fun checkInitialSystems() {
        if (!isNotificationServiceEnabled()) {
            showAssistDialog("OÍDO (Notificaciones)", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        } else {
            requestBatteryOptimizationBypass()
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        } else {
            Toast.makeText(this, "Visión Stark ya está activa", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, StarkCaptureService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun showAssistDialog(name: String, action: String) {
        AlertDialog.Builder(this)
            .setTitle("PROTOCOLO OMEGA")
            .setMessage("Señor, para que JARVIS pueda gritar los pagos, debe activar el $name.")
            .setCancelable(false)
            .setPositiveButton("ACTIVAR") { _, _ -> startActivity(Intent(action)) }
            .show()
    }

    private fun requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }
}
