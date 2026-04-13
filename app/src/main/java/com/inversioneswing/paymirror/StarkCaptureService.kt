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
        .setContentTitle("WING Sentinel v5.9")
        .setContentText("Vigilancia de Red Stark Activa")
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val fullContent = "$title $text".uppercase()

        // 1. CAPTURA DE PAGOS REALES
        if (pkg.contains("yape") || pkg.contains("bcp") || pkg.contains("plin") || pkg.contains("interbank") || pkg.contains("scotia")) {
            if (!processPayment(fullContent, pkg)) {
                // REPORTE DE CAJA NEGRA: Si el banco envió algo pero no lo entendimos
                serviceScope.launch {
                    sendToTelegram("⚠️ *AVISO DE JARVIS*\nSe detectó actividad en $pkg pero no se pudo extraer el monto.\n📜 Contenido: $fullContent")
                }
            }
        }

        // 2. COMANDOS REMOTOS (Telegram Bridge)
        if (pkg.contains("telegram")) {
            when {
                fullContent.contains("[ALERTA_SOS]") -> activarAlertaCritica()
                fullContent.contains("[TEST_PAGO]") -> {
                    val fakeContent = fullContent.replace("[TEST_PAGO]", "").trim()
                    processPayment(fakeContent, "yape_test")
                }
            }
        }
    }

    private fun processPayment(content: String, pkg: String): Boolean {
        // Regex mejorada para capturar S/ 10, S/ 10.00, S/10, etc.
        val regex = Pattern.compile("(S/|S/\\.|S/\\s*)\\s*([\\d,]+\\.\\d{2}|[\\d,]+)")
        val matcher = regex.matcher(content)
        
        if (matcher.find()) {
            val monto = matcher.group(2)?.replace(",", "") ?: "0.00"
            val nombre = content.replace(matcher.group(0)!!, "")
                .replace("¡YAPEASTE!", "")
                .replace("TE ENVIÓ", "")
                .replace("PAGO RECIBIDO", "")
                .replace("HAS RECIBIDO", "").trim()
            
            val banco = when {
                pkg.contains("yape") -> "YAPE"
                pkg.contains("bcp") -> "BCP"
                pkg.contains("plin") -> "PLIN"
                pkg.contains("test") -> "PRUEBA"
                else -> "BANCO"
            }

            speak("Nuevo pago de $nombre por $monto soles")
            
            serviceScope.launch {
                sendToTelegram("🚀 *PAGO CONFIRMADO*\n💰 Monto: S/ $monto\n👤 De: $nombre\n🏦 Banco: $banco")
            }
            return true
        }
        return false
    }

    private fun activarAlertaCritica() {
        speak("ALERTA DE PÁNICO ACTIVADA. EMERGENCIA EN LA RED STARK.")
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
            tts.speak(text, TextToSpeech.QUEUE_ADD, params, "STARK_ID")
        } else {
            pendingMessages.add(text)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
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
