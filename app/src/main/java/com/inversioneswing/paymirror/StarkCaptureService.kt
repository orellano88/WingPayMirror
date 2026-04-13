package com.inversioneswing.paymirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.regex.Pattern

class StarkCaptureService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private val CHANNEL_ID = "WING_OMEGA_CHANNEL"
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createPersistentNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
        
        // RECEPTOR DE VOZ DE PRUEBA (v5.6)
        intent?.getStringExtra("TEST_VOICE")?.let { message ->
            if (isTtsReady) speak(message)
        }

        if (!::tts.isInitialized) {
            tts = TextToSpeech(this, this)
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "WING Omega Sentinel", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("WING Sentinel v43.0 (OMEGA)")
        .setContentText("Intercepción en Segundo Plano Activa")
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg.contains("yape") || pkg.contains("bcp")) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            processPayment("$title $text", pkg)
        }
    }

    private fun processPayment(content: String, pkg: String) {
        val regex = Pattern.compile("S/\\s*([\\d,]+\\.\\d{2}|\\d+)")
        val matcher = regex.matcher(content)
        if (matcher.find()) {
            val monto = matcher.group(1)?.replace(",", "") ?: "0.00"
            val nombre = content.replace(matcher.group(0)!!, "").replace("¡Yapeaste!", "").replace("te envió", "").trim()
            val banco = if(pkg.contains("yape")) "YAPE" else "BCP"

            // 1. HABLAR (ALTAVOZ LOCAL)
            speak("Nuevo pago de $nombre por $monto soles")

            // 2. TELEGRAM (PUENTE REMOTO)
            serviceScope.launch {
                sendToTelegram("🚀 *PAGO DETECTADO*\n💰 Monto: S/ $monto\n👤 De: $nombre\n🏦 Banco: $banco")
            }
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PAY_ID")
    }

    private fun sendToTelegram(message: String) {
        val token = "8629465941:AAH-5rwmNDTP_91UKZIRrJO_oZ24p1IcIQE"
        val chatId = "8502345704"
        try {
            val url = URL("https://api.telegram.org/bot$token/sendMessage")
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(outputStream).use { it.write(JSONObject().apply { put("chat_id", chatId); put("text", message); put("parse_mode", "Markdown") }.toString()) }
                responseCode; disconnect()
            }
        } catch (e: Exception) {}
    }

    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) { tts.language = Locale("es", "ES"); isTtsReady = true } }
    override fun onDestroy() { serviceJob.cancel(); tts.shutdown(); super.onDestroy() }
}
