package com.inversioneswing.paymirror

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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

            LaunchedEffect(Unit) {
                while(true) {
                    try {
                        val connection = URL(dbUrl).openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
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
                    } catch (e: Exception) {
                        // Silently ignore network errors and retry
                    }
                    delay(3000)
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FF41), background = Color(0xFF0D0D0D))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D0D0D)) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("WING PAY-MIRROR // STARK-UI", color = Color(0xFF00F3FF), fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(paymentList) { payment ->
                                ChatBubble(payment)
                            }
                        }
                    }
                }
            }
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
}

@Composable
fun ChatBubble(p: Map<String, String>) {
    val color = if (p["banco"] == "YAPE") Color(0xFF25D366) else Color(0xFF00F3FF)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)).background(Color.White.copy(alpha = 0.05f)).border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)).padding(12.dp)) {
        Text(p["nombre"] ?: "", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("S/ ${p["monto"]}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}
