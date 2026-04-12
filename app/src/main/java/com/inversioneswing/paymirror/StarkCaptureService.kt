package com.inversioneswing.paymirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import kotlinx.coroutines.*

class StarkCaptureService : NotificationListenerService() {

    private val dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/pagos.json"
    private val statusUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/status.json"
    private val CHANNEL_ID = "WING_CORE_CHANNEL"
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private val processedNotifications = mutableSetOf<String>() // Para deduplicación simple en memoria

    private val allowedPackages = setOf(
        "com.viabcp.yape",
        "com.bcp.innovabcp",
        "com.viabcp.bcp"
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createPersistentNotification())
        
        serviceScope.launch {
            while(isActive) {
                sendHeartbeat()
                delay(300000) // 5 min
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
            .setContentText("Sincronización Neuronal Stark en curso...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (allowedPackages.contains(pkg) || pkg.contains("yape")) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
            
            val fullContent = "$title | $text | $bigText"
            val notificationId = sbn.id.toString() + sbn.postTime.toString() // ID único por notificación

            if (!processedNotifications.contains(notificationId)) {
                processedNotifications.add(notificationId)
                if (processedNotifications.size > 100) processedNotifications.remove(processedNotifications.first())
                
                processPayment(fullContent, pkg)
            }
        }
    }

    private fun processPayment(content: String, pkg: String) {
        val regex = Pattern.compile("S/\\s*([\\d,]+\\.\\d{2}|\\d+)")
        val matcher = regex.matcher(content)

        if (matcher.find()) {
            val monto = matcher.group(1)?.replace(",", "") ?: "0.00"
            val nombre = content.split("|")[0].trim() // Usar el título como posible nombre si es Yape
                                .replace("¡Yapeaste!", "")
                                .replace("te envió", "")
                                .replace("Pago recibido de", "").trim()

            val jsonBody = JSONObject().apply {
                put("nombre", if (nombre.isEmpty()) "Externo" else nombre)
                put("monto", monto)
                put("timestamp", System.currentTimeMillis())
                put("banco", if(pkg.contains("yape") || pkg.contains("innova")) "YAPE" else "BCP")
            }

            serviceScope.launch {
                postToFirebase(dbUrl, jsonBody.toString())
            }
        }
    }

    private fun sendHeartbeat() {
        val statusBody = JSONObject().apply {
            put("last_active", System.currentTimeMillis())
            put("status", "ONLINE")
            put("version", "23.0-QWEN-PROTOCOL")
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
            connection.readTimeout = 10000
            OutputStreamWriter(connection.outputStream).use { 
                it.write(data)
                it.flush()
            }
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
