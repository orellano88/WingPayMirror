package com.inversioneswing.paymirror

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // DISEÑO OMEGA MINIMALISTA (v5.1)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            gravity = Gravity.CENTER
            backgroundColor = 0xFF121212.toInt() // Modo Oscuro Stark
        }

        val title = TextView(this).apply {
            text = "WING SENTINEL OMEGA v5.1"
            textSize = 24f
            setTextColor(0xFFFF0000.toInt())
            textStyle = android.graphics.Typeface.BOLD
            setPadding(0, 0, 0, 40)
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "ESTADO: ESCANEANDO FRECUENCIAS\nTELEGRAM: SINCRONIZADO\nALTAVOZ: MÁXIMA POTENCIA"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }

        val btnSync = Button(this).apply {
            text = "REINICIAR OÍDO DE JARVIS"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { 
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Toast.makeText(context, "Desactive y vuelva a activar WingPay", Toast.LENGTH_LONG).show()
            }
        }

        val btnOverlay = Button(this).apply {
            text = "ACTIVAR VISIÓN SOBRE APPS"
            setBackgroundColor(0xFFFF0000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 20, 0, 20)
            setOnClickListener { requestOverlayPermission() }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(btnOverlay)
        val space = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) }
        layout.addView(space)
        layout.addView(btnSync)
        setContentView(layout)

        checkInitialSystems()
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
