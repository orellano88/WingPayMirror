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
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class StarkCaptureService : NotificationListenerService() {

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
            .setContentTitle("WING Triple Enlace Activo")
            .setContentText("Vigilando Yape, BCP y WhatsApp...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val fullContent = "$title $text"
        
        val notificationId = sbn.postTime.toString() + sbn.id
        if (processedNotifications.contains(notificationId)) return
        processedNotifications.add(notificationId)

        // 1. DETECCIÓN BANCARIA DIRECTA (Yape/BCP)
        if (pkg.contains("yape") || pkg.contains("bcp")) {
            processPayment(fullContent, pkg)
        } 
        // 2. DETECCIÓN WHATSAPP (Filtro de Pagos)
        else if (pkg.contains("whatsapp")) {
            val lowerContent = fullContent.lowercase()
            if (lowerContent.contains("yape") || lowerContent.contains("bcp") || lowerContent.contains("pago") || lowerContent.contains("transferencia")) {
                processWhatsAppPayment(title, text)
            }
        }
    }

    private fun processPayment(content: String, pkg: String) {
        val regex = Pattern.compile("S/\\s*([\\d,]+\\.\\d{2}|\\d+)")
        val matcher = regex.matcher(content)

        if (matcher.find()) {
            val monto = matcher.group(1)?.replace(",", "") ?: "0.00"
            val nombre = content.replace(matcher.group(0)!!, "").replace("¡Yapeaste!", "").replace("te envió", "").trim()
            val banco = if(pkg.contains("yape")) "YAPE" else "BCP"

            val msgTelegram = "🚀 *PAGO BANCARIO DETECTADO*\n\n💰 *Monto:* S/ $monto\n👤 *De:* $nombre\n🏦 *Banco:* $banco"
            
            serviceScope.launch {
                sendToTelegram(msgTelegram)
                postToFirebase("PAYMENT", monto, nombre, banco)
            }
        }
    }

    private fun processWhatsAppPayment(sender: String, message: String) {
        val msgTelegram = "🟢 *REPORTE WHATSAPP*\n\n👤 *De:* $sender\n💬 *Mensaje:* $message\n\n_Filtro de pago activado_"
        
        serviceScope.launch {
            sendToTelegram(msgTelegram)
            postToFirebase("WHATSAPP_PAY", "0.00", sender, message)
        }
    }

    private fun sendToTelegram(message: String) {
        val token = "8629465941:AAH-5rwmNDTP_91UKZIRrJO_oZ24p1IcIQE"
        val chatId = "8502345704"
        try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().apply { put("chat_id", chatId); put("text", message); put("parse_mode", "Markdown") }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()); it.flush() }
            conn.responseCode; conn.disconnect()
        } catch (e: Exception) {}
    }

    private fun postToFirebase(type: String, monto: String, nombre: String, content: String) {
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val urlStr = "https://wingpaymirror-default-rtdb.firebaseio.com/hives/$myId.json"
        try {
            val body = JSONObject().apply {
                put("type", type); put("nombre", nombre); put("monto", monto)
                put("timestamp", System.currentTimeMillis()); put("user", "SISTEMA"); put("content", content)
            }
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()); it.flush() }
            conn.responseCode; conn.disconnect()
        } catch (e: Exception) {}
    }

    override fun onDestroy() { serviceJob.cancel(); super.onDestroy() }
}
