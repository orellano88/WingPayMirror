package com.inversioneswing.paymirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
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
    private val pendingMessages = mutableListOf<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createPersistentNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
        
        intent?.getStringExtra("TEST_VOICE")?.let { speak(it) }

        if (!::tts.isInitialized) {
            tts = TextToSpeech(this, this)
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "WING Omega Sentinel", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Canal Crítico de JARVIS"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("WING Sentinel v6.0")
        .setContentText("Voz Humana Sincronizada")
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        
        // Mantener original para hablar, y mayúsculas solo para buscar
        val originalContent = "$title $text"
        val searchContent = originalContent.uppercase()

        // 1. CAPTURA DE PAGOS REALES
        if (pkg.contains("yape") || pkg.contains("bcp") || pkg.contains("plin") || pkg.contains("interbank") || pkg.contains("scotia")) {
            if (!processPayment(originalContent, pkg)) {
                serviceScope.launch {
                    sendToTelegram("⚠️ *AVISO DE JARVIS*\nSe detectó actividad en $pkg pero no se pudo extraer el monto.\n📜 Contenido: $originalContent")
                }
            }
        }

        // 2. COMANDOS REMOTOS
        if (pkg.contains("telegram")) {
            when {
                searchContent.contains("[ALERTA_SOS]") -> activarAlertaCritica()
                searchContent.contains("[TEST_PAGO]") -> {
                    val fakeContent = originalContent.replace("[TEST_PAGO]", "", true).trim()
                    processPayment(fakeContent, "yape_test")
                }
            }
        }
    }

    private fun processPayment(content: String, pkg: String): Boolean {
        val regex = Pattern.compile("(S/|S/\\.|S/\\s*)\\s*([\\d,]+\\.\\d{2}|[\\d,]+)")
        val matcher = regex.matcher(content)
        
        if (matcher.find()) {
            val monto = matcher.group(2)?.replace(",", "") ?: "0.00"
            // Limpiar nombre para que suene natural (sin mayúsculas forzadas)
            var nombre = content.replace(matcher.group(0)!!, "", true)
                .replace("¡Yapeaste!", "", true)
                .replace("te envió", "", true)
                .replace("Pago recibido", "", true)
                .replace("Has recibido", "", true).trim()

            if (nombre.isEmpty()) nombre = "Alguien"
            
            val banco = when {
                pkg.contains("yape") -> "Yape"
                pkg.contains("bcp") -> "B C P"
                pkg.contains("plin") -> "Plin"
                pkg.contains("test") -> "Prueba"
                else -> "Banco"
            }

            // HABLA NATURAL: "Nuevo pago de Juan Pérez por 10 soles 50"
            val montoParaHablar = monto.replace(".", " soles ")
            speak("Señor, nuevo pago de $nombre por $montoParaHablar céntimos")
            
            serviceScope.launch {
                sendToTelegram("🚀 *PAGO CONFIRMADO*\n💰 Monto: S/ $monto\n👤 De: $nombre\n🏦 Banco: $banco")
            }
            return true
        }
        return false
    }

    private fun activarAlertaCritica() {
        speak("Alerta de pánico activada. Emergencia detectada en la red Stark.")
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(5000)
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM)
            // Usar minúsculas y pausas para naturalidad
            tts.speak(text.lowercase(), TextToSpeech.QUEUE_ADD, params, "STARK_ID")
        } else {
            pendingMessages.add(text)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Intentar usar español de Latinoamérica si está disponible
            val result = tts.setLanguage(Locale("es", "MX"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.language = Locale("es", "ES")
            }
            isTtsReady = true
            while (pendingMessages.isNotEmpty()) {
                speak(pendingMessages.removeAt(0))
            }
        }
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

    override fun onDestroy() {
        serviceJob.cancel()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
