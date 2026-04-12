package com.inversioneswing.paymirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingServiceKey
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StarkCaptureService : NotificationListenerService() {

    private val dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/pagos.json"
    private val statusUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/status.json"
    private val CHANNEL_ID = "WING_CORE_CHANNEL"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createPersistentNotification())
        
        // Protocolo Heartbeat para WhatsApp Mini-IAs
        CoroutineScope(Dispatchers.IO).launch {
            while(true) {
                sendHeartbeat()
                kotlinx.coroutines.delay(300000) // Cada 5 min
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "WING Core Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WING Pay-Mirror Activo")
            .setContentText("Escaneando flujos de pago Stark...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == "com.bcp.innovabcp" || pkg == "com.viabcp.bcp" || pkg.contains("yape")) {
            val extras = sbn.notification.extras
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            
            processPayment("$text $bigText", pkg)
        }
    }

    private fun processPayment(content: String, pkg: String) {
        val regex = Pattern.compile("(S/|S/\\.|S/\\s)(\\d+\\.\\d{2}|\\d+)")
        val matcher = regex.matcher(content)

        if (matcher.find()) {
            val monto = matcher.group(2)
            val nombre = content.replace(matcher.group(0)!!, "")
                                .replace("¡Yapeaste!", "")
                                .replace("te envió", "").trim()

            val jsonBody = JSONObject().apply {
                put("nombre", nombre)
                put("monto", monto)
                put("timestamp", System.currentTimeMillis())
                put("banco", if(pkg.contains("innova") || pkg.contains("yape")) "YAPE" else "BCP")
            }

            CoroutineScope(Dispatchers.IO).launch {
                postToFirebase(dbUrl, jsonBody.toString())
            }
        }
    }

    private fun sendHeartbeat() {
        val statusBody = JSONObject().apply {
            put("last_active", System.currentTimeMillis())
            put("status", "ONLINE")
        }
        postToFirebase(statusUrl, statusBody.toString())
    }

    private fun postToFirebase(urlStr: String, data: String) {
        try {
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            OutputStreamWriter(connection.outputStream).use { 
                it.write(data)
                it.flush()
            }
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {}
    }
}
