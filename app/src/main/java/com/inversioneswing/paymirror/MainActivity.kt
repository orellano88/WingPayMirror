package com.inversioneswing.paymirror

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Base64
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
import java.io.*
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
    private var lastUpdateTag = ""
    private var isAlertActive = false
    private var mediaPlayer: MediaPlayer? = null
    
    private var neuralId = ""
    private val prefs by lazy { getSharedPreferences("STARK_PREFS", Context.MODE_PRIVATE) }
    private val dbBaseUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/hives"
    private val alertBaseUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/alerts"
    
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    // MOTOR DE CÁMARA REAL (Base64) - v37.9
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val bitmap = res.data?.extras?.get("data") as? Bitmap
            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
                sendToHive("IMAGE", base64)
                vibrate(100)
            }
        }
    }

    private val qrScanner = registerForActivityResult(ScanContract()) { res ->
        if (res.contents != null) {
            neuralId = res.contents
            prefs.edit().putString("NEURAL_ID", neuralId).apply()
            updateUIState(); restartSync()
            Toast.makeText(this, "Neural Link OK", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        neuralId = prefs.getString("NEURAL_ID", myId) ?: myId
        setupUI(); startNeuralSync()
    }

    private fun setupUI() {
        tts = TextToSpeech(this, this)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = HiveAdapter(contentList)
        recyclerView.adapter = adapter

        updateUIState()

        val et = findViewById<EditText>(R.id.etMessage)
        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            val txt = et.text.toString().trim()
            if (txt.isNotEmpty()) {
                et.setText("") // LIMPIEZA ATÓMICA
                sendToHive("MESSAGE", txt)
                vibrate(50)
            }
        }

        findViewById<ImageButton>(R.id.btnAttach).setOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            cameraLauncher.launch(intent)
        }

        findViewById<ImageButton>(R.id.btnPanic).setOnClickListener { triggerGlobalAlert() }
        findViewById<ImageButton>(R.id.btnQR).setOnClickListener { showQRMenu() }
    }

    private fun updateUIState() {
        val badge = findViewById<TextView>(R.id.tvRoleBadge) ?: return
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        if (neuralId == myId) {
            badge.text = "MODO MAESTRO"
            badge.backgroundTintList = ColorStateList.valueOf(0x33000000)
        } else {
            badge.text = "MODO ESPEJO"
            badge.backgroundTintList = ColorStateList.valueOf(0x33FF0000.toInt())
        }
    }

    private fun startNeuralSync() {
        activityScope.launch(Dispatchers.IO) {
            while(isActive) {
                try { syncData(); syncAlert() } catch (e: Exception) {}
                delay(3000)
            }
        }
    }

    private suspend fun syncData() {
        val json = httpGet("$dbBaseUrl/$neuralId.json?limitToLast=30")
        if (TextUtils.isEmpty(json) || json == "null") return
        val root = JSONObject(json)
        val newList = mutableListOf<Map<String, String>>()
        root.keys().forEach { key ->
            val obj = root.optJSONObject(key) ?: return@forEach
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { map[it] = obj.optString(it, "") }
            newList.add(map)
        }
        val sorted = newList.sortedBy { it["timestamp"]?.toLongOrNull() ?: 0L }
        val currentTag = if (sorted.isNotEmpty()) sorted.last()["timestamp"] + sorted.last()["content"] else ""
        if (currentTag != lastUpdateTag) {
            val isFirst = lastUpdateTag == ""
            lastUpdateTag = currentTag
            withContext(Dispatchers.Main) {
                contentList.clear(); contentList.addAll(sorted); adapter.notifyDataSetChanged()
                if (contentList.isNotEmpty()) recyclerView.scrollToPosition(contentList.size - 1)
                if (!isFirst && sorted.last()["type"] == "PAYMENT") speakPayment(sorted.last()["nombre"] ?: "Externo", sorted.last()["monto"] ?: "0")
            }
        }
    }

    private suspend fun syncAlert() {
        val json = httpGet("$alertBaseUrl/$neuralId.json")
        val active = json.contains("ACTIVE")
        withContext(Dispatchers.Main) {
            val overlay = findViewById<View>(R.id.alert_overlay) ?: return@withContext
            if (active && !isAlertActive) { startPanicAlarm(); overlay.visibility = View.VISIBLE }
            else if (!active && isAlertActive) { stopPanicAlarm(); overlay.visibility = View.GONE }
        }
    }

    private fun triggerGlobalAlert() {
        val next = if (isAlertActive) "IDLE" else "ACTIVE"
        activityScope.launch(Dispatchers.IO) { httpPut("$alertBaseUrl/$neuralId.json", JSONObject().put("status", next).toString()) }
    }

    private fun sendToHive(type: String, content: String) {
        val body = JSONObject().apply {
            put("type", type); put("content", content); put("user", Build.MODEL); put("timestamp", System.currentTimeMillis())
        }
        activityScope.launch(Dispatchers.IO) { httpPost("$dbBaseUrl/$neuralId.json", body.toString()) }
    }

    private fun httpGet(urlStr: String) = try { (URL(urlStr).openConnection() as HttpURLConnection).inputStream.bufferedReader().use { it.readText() } } catch(e: Exception) { "" }
    private fun httpPost(urlStr: String, data: String) { try { (URL(urlStr).openConnection() as HttpURLConnection).apply { requestMethod="POST"; doOutput=true; setRequestProperty("Content-Type", "application/json"); outputStream.bufferedWriter().use { it.write(data) }; responseCode } } catch(e: Exception) {} }
    private fun httpPut(urlStr: String, data: String) { try { (URL(urlStr).openConnection() as HttpURLConnection).apply { requestMethod="PUT"; doOutput=true; setRequestProperty("Content-Type", "application/json"); outputStream.bufferedWriter().use { it.write(data) }; responseCode } } catch(e: Exception) {} }

    private fun startPanicAlarm() {
        isAlertActive = true; mediaPlayer = MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        mediaPlayer?.isLooping = true; mediaPlayer?.start(); vibrate(1000)
    }

    private fun stopPanicAlarm() {
        isAlertActive = false; mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else v.vibrate(ms)
    }

    private fun showQRMenu() {
        val opt = arrayOf("Mi QR Maestro", "Escanear Espejo", "Reiniciar")
        AlertDialog.Builder(this).setItems(opt) { _, i ->
            when(i) {
                0 -> showMyQR()
                1 -> qrScanner.launch(ScanOptions().setPrompt("Apunta al QR Maestro"))
                2 -> { prefs.edit().clear().apply(); neuralId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID); updateUIState(); restartSync() }
            }
        }.show()
    }

    private fun showMyQR() {
        val mid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val matrix = MultiFormatWriter().encode(mid, BarcodeFormat.QR_CODE, 500, 500)
        val iv = ImageView(this).apply { setImageBitmap(BarcodeEncoder().createBitmap(matrix)); setPadding(40,40,40,40) }
        AlertDialog.Builder(this).setTitle("ID Maestro").setView(iv).show()
    }

    private fun restartSync() { contentList.clear(); adapter.notifyDataSetChanged(); lastUpdateTag = "" }
    private fun speakPayment(n: String, m: String) { if (isTtsReady) tts.speak("Pago de $n por $m soles", TextToSpeech.QUEUE_FLUSH, null, null) }
    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) { tts.language = Locale("es", "ES"); isTtsReady = true } }
    override fun onDestroy() { activityJob.cancel(); stopPanicAlarm(); super.onDestroy() }
}

class HiveAdapter(private val list: List<Map<String, String>>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemViewType(pos: Int) = if (list[pos]["type"] == "PAYMENT") 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) PaymentViewHolder(inf.inflate(R.layout.item_payment, parent, false))
        else MessageViewHolder(inf.inflate(R.layout.item_message, parent, false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val d = list[pos]; val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d["timestamp"]?.toLongOrNull() ?: 0L))
        val user = d["user"] ?: "SISTEMA"
        val isMe = user == "SISTEMA" || user == Build.MODEL
        
        if (holder is PaymentViewHolder) {
            holder.tvNombre.text = d["nombre"]; holder.tvMonto.text = "S/ ${d["monto"]}"; holder.tvBanco.text = "${d["banco"]} • $time"
            holder.llBubble.gravity = if (isMe) Gravity.END else Gravity.START
            holder.llBubble.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (isMe) "#E1FFC7" else "#FFFFFF"))
        } else if (holder is MessageViewHolder) {
            holder.tvUser.text = user; holder.tvTime.text = time
            holder.llBubble.gravity = if (isMe) Gravity.END else Gravity.START
            holder.llBubble.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (isMe) "#E1FFC7" else "#FFFFFF"))

            val content = d["content"] ?: ""
            if (d["type"] == "IMAGE") {
                holder.tvMessage.visibility = View.GONE; holder.ivContent.visibility = View.VISIBLE
                try {
                    val decoded = Base64.decode(content, Base64.DEFAULT)
                    holder.ivContent.setImageBitmap(BitmapFactory.decodeByteArray(decoded, 0, decoded.size))
                } catch (e: Exception) { holder.ivContent.setImageResource(android.R.drawable.ic_menu_gallery) }
            } else {
                holder.ivContent.visibility = View.GONE; holder.tvMessage.visibility = View.VISIBLE; holder.tvMessage.text = content
            }
        }
    }
    override fun getItemCount() = list.size
    class PaymentViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvNombre = v.findViewById<TextView>(R.id.tvNombre); val tvMonto = v.findViewById<TextView>(R.id.tvMonto); val tvBanco = v.findViewById<TextView>(R.id.tvBanco); val llBubble = v.findViewById<LinearLayout>(R.id.llBubble)
    }
    class MessageViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvUser = v.findViewById<TextView>(R.id.tvUser); val tvMessage = v.findViewById<TextView>(R.id.tvMessage); val tvTime = v.findViewById<TextView>(R.id.tvTime); val ivContent = v.findViewById<ImageView>(R.id.ivContent); val llBubble = v.findViewById<LinearLayout>(R.id.llBubble)
    }
}
