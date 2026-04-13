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
        
        // DISEÑO MAESTRO MINIMALISTA (v42.1)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            gravity = android.view.Gravity.CENTER
            backgroundColor = 0xFFF0F2F5.toInt()
        }

        val title = TextView(this).apply {
            text = "WING SENTINEL v42.1"
            textSize = 26f
            setTextColor(0xFFFF0000.toInt())
            textStyle = android.graphics.Typeface.BOLD
            setPadding(0, 0, 0, 40)
        }

        val status = TextView(this).apply {
            text = "ENLACE TELEGRAM: ACTIVO\nALTAVOZ: STANDBY"
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }

        val btnCheck = Button(this).apply {
            text = "VERIFICAR SISTEMAS"
            setBackgroundColor(0xFFFF0000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(40, 20, 40, 20)
            setOnClickListener { checkSystem() }
        }

        val footer = TextView(this).apply {
            text = "JARVIS & QWEN — OPERACIÓN PURE"
            textSize = 10f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 100, 0, 0)
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(btnCheck)
        layout.addView(footer)
        setContentView(layout)

        checkSystem()
    }

    private fun checkSystem() {
        if (!isNotificationServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("ACTIVACIÓN REQUERIDA")
                .setMessage("Señor, debe encender el Oído de JARVIS. Seleccione 'WingPay Mirror' en la siguiente pantalla.")
                .setCancelable(false)
                .setPositiveButton("ACEPTAR") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.show()
        } else {
            requestBatteryOptimizationBypass()
            Toast.makeText(this, "SISTEMAS EN LÍNEA", Toast.LENGTH_SHORT).show()
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
