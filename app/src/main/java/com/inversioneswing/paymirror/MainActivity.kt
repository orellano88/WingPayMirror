package com.inversioneswing.paymirror

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.util.*

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private val dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/pagos.json?orderBy=\"timestamp\"&limitToLast=20"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        setContent {
            val paymentList = remember { mutableStateListOf<Map<String, String>>() }
            var lastId by remember { mutableStateOf("") }
            var isSyncing by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                while(true) {
                    isSyncing = true
                    try {
                        val connection = URL(dbUrl).openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10000
                        val json = try {
                            BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                        } finally {
                            connection.disconnect()
                        }

                        if (json.isNotEmpty() && json != "null") {
                            val root = JSONObject(json)
                            val keys = root.keys()
                            val allPayments = mutableListOf<Map<String, String>>()

                            while (keys.hasNext()) {
                                val key = keys.next()
                                val obj = root.getJSONObject(key)
                                val map = mutableMapOf<String, String>()
                                val innerKeys = obj.keys()
                                while (innerKeys.hasNext()) {
                                    val k = innerKeys.next()
                                    map[k] = obj.optString(k, "")
                                }
                                allPayments.add(map)
                            }

                            val sortedPayments = allPayments.sortedByDescending { it["timestamp"] }

                            if (sortedPayments.isNotEmpty() && sortedPayments[0]["timestamp"] != lastId) {
                                paymentList.clear()
                                paymentList.addAll(sortedPayments)
                                val newOne = sortedPayments[0]
                                lastId = newOne["timestamp"] ?: ""
                                speakPayment(newOne["nombre"] ?: "Externo", newOne["monto"] ?: "0")
                            }
                        }
                    } catch (e: Exception) {}
                    isSyncing = false
                    delay(5000)
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FF41), background = Color(0xFF050505))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF050505)) {
                    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        HeaderSection(isSyncing)
                        Spacer(modifier = Modifier.height(20.dp))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(paymentList) { payment ->
                                SimpleChatBubble(payment)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HeaderSection(isSyncing: Boolean) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("WING PAY-MIRROR", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("WHATSAPP AI SYNC ACTIVE", color = Color(0xFF00F3FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(if(isSyncing) Color(0xFF00FF41) else Color(0xFF333333)))
        }
    }

    private fun speakPayment(nombre: String, monto: String) {
        if (isTtsReady) tts.speak("Pago de $nombre por $monto soles", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            isTtsReady = true
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}

@Composable
fun SimpleChatBubble(p: Map<String, String>) {
    val accentColor = if (p["banco"] == "YAPE") Color(0xFF25D366) else Color(0xFF00F3FF)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p["nombre"] ?: "Desconocido", color = accentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(p["banco"] ?: "", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text("S/ ${p["monto"]}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
    }
}
