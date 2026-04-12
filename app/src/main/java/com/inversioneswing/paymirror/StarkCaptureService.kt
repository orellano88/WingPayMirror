package com.inversioneswing.paymirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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

    private val dbBaseUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/hives"
    private val CHANNEL_ID = "WING_CORE_CHANNEL"
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private val processedNotifications = mutableSetOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, createPersistentNotification())
        return START_STICKY
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
            .setContentText("Escaneando flujos de pago...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg.contains("yape") || pkg.contains("bcp")) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val fullContent = "$title $text"
            
            val notificationId = sbn.postTime.toString() + sbn.id
            if (!processedNotifications.contains(notificationId)) {
                processedNotifications.add(notificationId)
                processPayment(fullContent, pkg)
            }
        }
    }

    private fun processPayment(content: String, pkg: String) {
        val regex = Pattern.compile("S/\\s*([\\d,]+\\.\\d{2}|\\d+)")
        val matcher = regex.matcher(content)

        if (matcher.find()) {
            val monto = matcher.group(1)?.replace(",", "") ?: "0.00"
            val nombre = content.replace(matcher.group(0)!!, "").replace("¡Yapeaste!", "").replace("te envió", "").trim()

            val jsonBody = JSONObject().apply {
                put("type", "PAYMENT")
                put("nombre", if (nombre.length > 20) nombre.take(20) else nombre)
                put("monto", monto)
                put("timestamp", System.currentTimeMillis())
                put("user", "SISTEMA")
                put("banco", if(pkg.contains("yape")) "YAPE" else "BCP")
            }

            serviceScope.launch {
                val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                postToFirebase("$dbBaseUrl/$myId.json", jsonBody.toString())
            }
        }
    }

    private fun postToFirebase(urlStr: String, data: String) {
        try {
            val connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream).use { it.write(data); it.flush() }
            connection.responseCode
            connection.disconnect()
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
