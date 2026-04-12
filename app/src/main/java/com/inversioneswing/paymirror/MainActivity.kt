package com.inversioneswing.paymirror

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: HiveAdapter
    private val contentList = mutableListOf<Map<String, String>>()
    private var lastId = ""
    private var isAlertActive = false
    private var mediaPlayer: MediaPlayer? = null
    
    private var neuralId = "" // El ID de sincronización
    private val prefs by lazy { getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE) }

    private var dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/hives"
    private var alertUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/alerts"
    
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            saveNeuralLink(result.contents)
            Toast.makeText(this, "Enlace Neuronal Establecido", Toast.LENGTH_LONG).show()
            restartSync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadNeuralLink()
        setupUI()
        startSyncLoops()
    }

    private fun loadNeuralLink() {
        // Por defecto usa el ID del propio dispositivo (Master)
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        neuralId = prefs.getString("NEURAL_ID", myId) ?: myId
    }

    private fun saveNeuralLink(id: String) {
        neuralId = id
        prefs.edit().putString("NEURAL_ID", id).apply()
    }

    private fun setupUI() {
        tts = TextToSpeech(this, this)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = HiveAdapter(contentList)
        recyclerView.adapter = adapter

        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            val et = findViewById<EditText>(R.id.etMessage)
            if (et.text.isNotEmpty()) {
                sendToHive("MESSAGE", et.text.toString())
                et.text.clear()
            }
        }

        findViewById<ImageButton>(R.id.btnPanic).setOnClickListener { triggerGlobalAlert() }

        findViewById<ImageButton>(R.id.btnQR).setOnClickListener { showQRMenu() }
    }

    private fun showQRMenu() {
        val options = arrayOf("Generar Mi QR (Ser Maestro)", "Escanear QR (Ser Espejo)", "Restablecer Conexión")
        AlertDialog.Builder(this)
            .setTitle("MODO ESPEJO STARK")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Generar Mi QR (Ser Maestro)" -> showMyQR()
                    "Escanear QR (Ser Espejo)" -> startScanning()
                    "Restablecer Conexión" -> {
                        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                        saveNeuralLink(myId)
                        restartSync()
                    }
                }
            }.show()
    }

    private fun showMyQR() {
        try {
            val writer = MultiFormatWriter()
            val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val matrix = writer.encode(myId, BarcodeFormat.QR_CODE, 600, 600)
            val encoder = BarcodeEncoder()
            val bitmap: Bitmap = encoder.createBitmap(matrix)

            val iv = ImageView(this)
            iv.setImageBitmap(bitmap)
            iv.setPadding(40, 40, 40, 40)

            AlertDialog.Builder(this)
                .setTitle("Escanea para Vincular")
                .setView(iv)
                .setPositiveButton("Cerrar", null)
                .show()
        } catch (e: Exception) {}
    }

    private fun startScanning() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Escanea el QR del Celular Maestro")
        options.setBeepEnabled(true)
        options.setOrientationLocked(false)
        qrScanner.launch(options)
    }

    private fun restartSync() {
        contentList.clear()
        adapter.notifyDataSetChanged()
        lastId = ""
    }

    private fun startSyncLoops() {
        activityScope.launch {
            while(isActive) {
                fetchHiveContent()
                checkGlobalAlert()
                delay(3000)
            }
        }
    }

    private fun fetchHiveContent() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val fullUrl = "$dbUrl/$neuralId.json?limitToLast=30"
                val json = httpGet(fullUrl)
                if (!TextUtils.isEmpty(json) && json != "null") {
                    val root = JSONObject(json)
                    val keys = root.keys()
                    val newList = mutableListOf<Map<String, String>>()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = root.optJSONObject(key) ?: continue
                        val map = mutableMapOf<String, String>()
                        obj.keys().forEach { map[it] = obj.optString(it, "") }
                        newList.add(map)
                    }
                    val sorted = newList.sortedBy { it["timestamp"] }
                    withContext(Dispatchers.Main) {
                        if (sorted.isNotEmpty() && sorted.last()["timestamp"] != lastId) {
                            val isFirst = lastId == ""
                            lastId = sorted.last()["timestamp"] ?: ""
                            contentList.clear()
                            contentList.addAll(sorted)
                            adapter.notifyDataSetChanged()
                            recyclerView.smoothScrollToPosition(contentList.size - 1)
                            if (!isFirst && sorted.last()["type"] == "PAYMENT") {
                                speakPayment(sorted.last()["nombre"] ?: "Externo", sorted.last()["monto"] ?: "0")
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun checkGlobalAlert() {
        activityScope.launch(Dispatchers.IO) {
            try {
                val json = httpGet("$alertUrl/$neuralId.json")
                withContext(Dispatchers.Main) {
                    if (json.contains("ACTIVE") && !isAlertActive) startPanicAlarm()
                    else if (json.contains("IDLE") && isAlertActive) stopPanicAlarm()
                }
            } catch (e: Exception) {}
        }
    }

    private fun triggerGlobalAlert() {
        val next = if (isAlertActive) "IDLE" else "ACTIVE"
        activityScope.launch(Dispatchers.IO) {
            httpPut("$alertUrl/$neuralId.json", JSONObject().put("status", next).toString())
        }
    }

    private fun sendToHive(type: String, content: String) {
        val body = JSONObject().apply {
            put("type", type); put("content", content); put("user", Build.MODEL)
            put("timestamp", System.currentTimeMillis())
        }
        activityScope.launch(Dispatchers.IO) { httpPost("$dbUrl/$neuralId.json", body.toString()) }
    }

    private fun httpGet(urlStr: String): String {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        return try { BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() } } 
        finally { connection.disconnect() }
    }

    private fun httpPost(urlStr: String, data: String) {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream).use { it.write(data); it.flush() }
            connection.responseCode
        } finally { connection.disconnect() }
    }

    private fun httpPut(urlStr: String, data: String) {
        val connection = URL(urlStr).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream).use { it.write(data); it.flush() }
            connection.responseCode
        } finally { connection.disconnect() }
    }

    private fun startPanicAlarm() {
        isAlertActive = true
        mediaPlayer = MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        mediaPlayer?.isLooping = true; mediaPlayer?.start()
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 500, 1000), 0))
    }

    private fun stopPanicAlarm() {
        isAlertActive = false; mediaPlayer?.stop(); mediaPlayer?.release()
        mediaPlayer = null; (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
    }

    private fun speakPayment(n: String, m: String) { if (isTtsReady) tts.speak("Pago de $n por $m soles", TextToSpeech.QUEUE_FLUSH, null, null) }
    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) { tts.language = Locale("es", "ES"); isTtsReady = true } }
    override fun onDestroy() { activityJob.cancel(); stopPanicAlarm(); super.onDestroy() }
}
