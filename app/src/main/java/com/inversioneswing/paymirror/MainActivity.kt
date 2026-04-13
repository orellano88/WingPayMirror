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
        
        // DISEÑO OMEGA SOS (v5.7)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
        }

        val title = TextView(this).apply {
            text = "WING SENTINEL v5.7 OMEGA SOS"
            textSize = 24f
            setTextColor(0xFFFF0000.toInt()) // Rojo Alerta
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 40)
            gravity = Gravity.CENTER
        }

        val btnSOS = Button(this).apply {
            text = "🚨 BOTÓN DE PÁNICO SOS 🚨"
            textSize = 20f
            setBackgroundColor(0xFFFF0000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 60, 0, 60)
            setOnClickListener { 
                enviarAlertaSOS()
            }
        }

        val btnTestVoice = Button(this).apply {
            text = "🔊 PROBAR VOZ DE JARVIS"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { 
                val intent = Intent(this@MainActivity, StarkCaptureService::class.java)
                intent.putExtra("TEST_VOICE", "Señor, sistema listo.")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        }
        
        layout.addView(title)
        layout.addView(btnSOS)
        val space = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) }
        layout.addView(space)
        layout.addView(btnTestVoice)
        // ... (resto de botones)

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

    private fun enviarAlertaSOS() {
        val token = "8629465941:AAH-5rwmNDTP_91UKZIRrJO_oZ24p1IcIQE"
        val chatId = "8502345704"
        val message = "🚨 [ALERTA_SOS] ¡BOTÓN DE PÁNICO ACTIVADO! 🚨\nSe requiere asistencia inmediata."
        
        Thread {
            try {
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json")
                    OutputStreamWriter(outputStream).use { it.write(JSONObject().apply { put("chat_id", chatId); put("text", message); put("parse_mode", "Markdown") }.toString()) }
                    responseCode; disconnect()
                }
                runOnUiThread { Toast.makeText(this, "SOS ENVIADO A LA RED", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {}
        }.start()
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
