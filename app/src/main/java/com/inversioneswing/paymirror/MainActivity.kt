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
import android.net.Uri
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
    
    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data?.extras?.get("data") as? Bitmap
            if (data != null) {
                val stream = ByteArrayOutputStream()
                data.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
                sendToHive("IMAGE", base64)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        neuralId = prefs.getString("NEURAL_ID", myId) ?: myId
        
        setupUI()
        startNeuralSync()
        requestImmortality()
    }

    private fun setupUI() {
        tts = TextToSpeech(this, this)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = HiveAdapter(contentList)
        recyclerView.adapter = adapter

        updateUIState()

        // LÓGICA DE CONFIGURACIÓN TELEGRAM
        val layoutSettings = findViewById<LinearLayout>(R.id.layoutSettings)
        val etToken = findViewById<EditText>(R.id.etBotToken)
        val etChatId = findViewById<EditText>(R.id.etChatId)
        
        etToken.setText("8629465941:AAH-5rwmNDTP_91UKZIRrJO_oZ24p1IcIQE")
        etChatId.setText("1775956659")

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            layoutSettings.visibility = if (layoutSettings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            prefs.edit().apply {
                putString("TG_TOKEN", etToken.text.toString().trim())
                putString("TG_CHAT_ID", etChatId.text.toString().trim())
                apply()
            }
            layoutSettings.visibility = View.GONE
            Toast.makeText(this, "Enlace Neuronal Telegram Guardado", Toast.LENGTH_SHORT).show()
        }

        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            val et = findViewById<EditText>(R.id.etMessage)
            val txt = et.text.toString().trim()
            if (txt.isNotEmpty()) { et.setText(""); sendToHive("MESSAGE", txt); vibrate(50) }
        }

        findViewById<ImageButton>(R.id.btnAttach).setOnClickListener {
            cameraLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        }
    }

    private fun requestImmortality() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

    private fun startNeuralSync() {
        activityScope.launch(Dispatchers.IO) {
            while(isActive) {
                try { syncData() } catch (e: Exception) {}
                delay(5000)
            }
        }
    }

    private suspend fun syncData() {
        val json = httpGet("$dbBaseUrl/$neuralId.json?limitToLast=20")
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
        val currentTag = if (sorted.isNotEmpty()) sorted.last()["timestamp"] + (sorted.last()["content"] ?: "") else ""
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

    private fun sendToHive(type: String, content: String) {
        val body = JSONObject().apply {
            put("type", type); put("content", content); put("user", Build.MODEL); put("timestamp", System.currentTimeMillis())
        }
        activityScope.launch(Dispatchers.IO) { httpPost("$dbBaseUrl/$neuralId.json", body.toString()) }
    }

    private fun httpGet(urlStr: String) = try { (URL(urlStr).openConnection() as HttpURLConnection).apply { connectTimeout=5000 }.inputStream.bufferedReader().use { it.readText() } } catch(e: Exception) { "" }
    private fun httpPost(urlStr: String, data: String) { try { (URL(urlStr).openConnection() as HttpURLConnection).apply { requestMethod="POST"; doOutput=true; setRequestProperty("Content-Type", "application/json"); outputStream.bufferedWriter().use { it.write(data) }; responseCode } } catch(e: Exception) {} }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else v.vibrate(ms)
    }

    private fun updateUIState() {
        val badge = findViewById<TextView>(R.id.tvRoleBadge) ?: return
        val myId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        badge.text = if (neuralId == myId) "MAESTRO • TELEGRAM ACTIVE" else "ESPEJO • TELEGRAM ACTIVE"
    }

    private fun speakPayment(n: String, m: String) { 
        if (isTtsReady) {
            val text = "Señor, nuevo pago de $n por $m soles"
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PAY_ID")
        }
    }

    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) { tts.language = Locale("es", "ES"); isTtsReady = true } }
    override fun onDestroy() { activityJob.cancel(); super.onDestroy() }
}

class HiveAdapter(private val list: List<Map<String, String>>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemViewType(pos: Int) = if (list[pos]["type"] == "PAYMENT") 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) PaymentViewHolder(inf.inflate(R.layout.item_payment, parent, false))
        else MessageViewHolder(inf.inflate(R.layout.item_message, parent, false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        try {
            val d = list[pos]; val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d["timestamp"]?.toLongOrNull() ?: 0L))
            val user = d["user"] ?: "SISTEMA"; val isMe = user == "SISTEMA" || user == Build.MODEL
            
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
                    try { val decoded = Base64.decode(content, Base64.DEFAULT); holder.ivContent.setImageBitmap(BitmapFactory.decodeByteArray(decoded, 0, decoded.size)) } 
                    catch (e: Exception) { holder.ivContent.setImageResource(android.R.drawable.ic_menu_gallery) }
                } else {
                    holder.ivContent.visibility = View.GONE; holder.tvMessage.visibility = View.VISIBLE; holder.tvMessage.text = content
                }
            }
        } catch (e: Exception) {}
    }
    override fun getItemCount() = list.size
    class PaymentViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvNombre = v.findViewById<TextView>(R.id.tvNombre); val tvMonto = v.findViewById<TextView>(R.id.tvMonto); val tvBanco = v.findViewById<TextView>(R.id.tvBanco); val llBubble = v.findViewById<LinearLayout>(R.id.llBubble)
    }
    class MessageViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvUser = v.findViewById<TextView>(R.id.tvUser); val tvMessage = v.findViewById<TextView>(R.id.tvMessage); val tvTime = v.findViewById<TextView>(R.id.tvTime); val ivContent = v.findViewById<ImageView>(R.id.ivContent); val llBubble = v.findViewById<LinearLayout>(R.id.llBubble)
    }
}
