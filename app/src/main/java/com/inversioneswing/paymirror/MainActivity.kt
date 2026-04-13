package com.inversioneswing.paymirror

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // DISEÑO DINÁMICO MINIMALISTA (v42.0)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            gravity = android.view.Gravity.CENTER
            backgroundColor = 0xFFF0F2F5.toInt()
        }

        val title = TextView(this).apply {
            text = "WING SENTINEL v42.0"
            textSize = 24f
            setTextColor(0xFFFF0000.toInt())
            textStyle = android.graphics.Typeface.BOLD
            setPadding(0, 0, 0, 50)
        }

        val status = TextView(this).apply {
            text = "ESTADO: VIGILANDO"
            textSize = 16f
            setTextColor(0xFF000000.toInt())
            setPadding(0, 0, 0, 100)
        }

        val btnSync = Button(this).apply {
            text = "VERIFICAR ENLACE NEURONAL"
            setBackgroundColor(0xFFFF0000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { checkSystem() }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(btnSync)
        setContentView(layout)

        checkSystem()
    }

    private fun checkSystem() {
        if (!isNotificationServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("ACTIVACIÓN NECESARIA")
                .setMessage("Pulse ACEPTAR para encender el Oído de JARVIS (Acceso a Notificaciones).")
                .setPositiveButton("ACEPTAR") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.show()
        } else {
            requestBatteryOptimizationBypass()
            Toast.makeText(this, "SISTEMAS OPERATIVOS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, StarkCaptureService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
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
