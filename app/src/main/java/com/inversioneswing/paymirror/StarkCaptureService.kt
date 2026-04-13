package com.inversioneswing.paymirror

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import android.media.AudioManager
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
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WingPay:WakeLock")
    }

    private fun awakeAndSpeak(text: String) {
        if (!wakeLock.isHeld) { wakeLock.acquire(15 * 1000L) }
        
        // FORZAR VOLUMEN (Solución Huawei)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        
        speak(text)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createPersistentNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notification)
        }
        intent?.getStringExtra("TEST_VOICE")?.let { awakeAndSpeak(it) }
        if (!::tts.isInitialized) { tts = TextToSpeech(this, this) }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "WING Omega Sentinel", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createPersistentNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("WING Sentinel v7.2")
        .setContentText("Sincronización Stark Perfecta")
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName.lowercase()
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val fullContent = "$title $text"

        // GATILLO DE COMANDOS REMOTOS (Telegram)
        if (pkg.contains("telegram")) {
            if (fullContent.uppercase().contains("[TEST_PAGO]")) {
                val fakeContent = fullContent.replace("[TEST_PAGO]", "", true).trim()
                processSmartContent(fakeContent, "yape_test")
                return
            }
            if (fullContent.uppercase().contains("[ALERTA_SOS]")) {
                activarAlertaCritica()
                return
            }
        }

        // CAPTURA DE PAGOS REALES
        if (pkg.contains("yape") || pkg.contains("bcp") || pkg.contains("plin") || pkg.contains("interbank")) {
            processSmartContent(fullContent, pkg)
        }
    }

    private fun processSmartContent(content: String, pkg: String) {
        val regex = Pattern.compile("(S/|S/\\.|S/\\s*)\\s*([\\d,]+\\.\\d{2}|[\\d,]+)")
        val matcher = regex.matcher(content)
        
        if (matcher.find()) {
            val montoRaw = matcher.group(2)?.replace(",", "") ?: "0.00"
            var nombreRaw = content.replace(matcher.group(0)!!, "", true)
                .replace("¡Yapeaste!", "", true)
                .replace("te envió", "", true)
                .replace("Pago recibido", "", true)
                .replace(Regex("[^\\p{L}\\s]"), "") // Solo letras
                .trim()

            if (nombreRaw.isEmpty()) nombreRaw = "un cliente"
            val nombreLimpio = formatTitleCase(nombreRaw)
            
            val banco = when {
                pkg.contains("yape") -> "Yape"
                pkg.contains("bcp") -> "B C P"
                pkg.contains("plin") -> "Plin"
                else -> "su cuenta"
            }

            val soles = montoRaw.split(".")[0]
            val centimos = if (montoRaw.contains(".")) montoRaw.split(".")[1] else "00"
            
            val mensajeFinal = "Señor, $nombreLimpio le ha enviado $soles soles con $centimos céntimos a través de $banco."
            awakeAndSpeak(mensajeFinal)
            
            serviceScope.launch {
                sendToTelegram("🚀 *PAGO CONFIRMADO*\n💰 Monto: S/ $montoRaw\n👤 De: $nombreLimpio\n🏦 Banco: $banco")
            }
        }
    }

    private fun formatTitleCase(str: String): String {
        return str.lowercase().split(" ").filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }

    private fun activarAlertaCritica() {
        awakeAndSpeak("Alerta de pánico activada en la red Stark.")
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            val params = Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            tts.speak(text.lowercase(), TextToSpeech.QUEUE_ADD, params, "STARK_ID")
        } else { pendingMessages.add(text) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "MX")
            isTtsReady = true
            while (pendingMessages.isNotEmpty()) { speak(pendingMessages.removeAt(0)) }
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
        if (::tts.isInitialized) { tts.shutdown() }
        super.onDestroy()
    }
}
