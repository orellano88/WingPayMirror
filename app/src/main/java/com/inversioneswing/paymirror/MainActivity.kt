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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import java.util.*

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private val client = OkHttpClient()
    private val dbUrl = "https://wingpaymirror-default-rtdb.firebaseio.com/pagos.json?orderBy=\"timestamp\"&limitToLast=20"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        setContent {
            val paymentList = remember { mutableStateListOf<Map<String, String>>() }
            var isConnecting by remember { mutableStateOf(true) }
            var lastId by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                while(true) {
                    try {
                        val request = Request.Builder().url(dbUrl).build()
                        val response = client.newCall(request).execute()
                        val json = response.body?.string() ?: ""
                        val type = object : TypeToken<Map<String, Map<String, String>>>() {}.type
                        val data: Map<String, Map<String, String>> = Gson().fromJson(json, type) ?: emptyMap()
                        val sortedPayments = data.values.sortedBy { it["timestamp"] }
                        
                        if (sortedPayments.isNotEmpty() && sortedPayments.last()["timestamp"] != lastId) {
                            val newPayment = sortedPayments.last()
                            paymentList.clear()
                            paymentList.addAll(sortedPayments.reversed())
                            lastId = newPayment["timestamp"] ?: ""
                            isConnecting = false
                            speakPayment(newPayment["nombre"] ?: "Externo", newPayment["monto"] ?: "0")
                        }
                    } catch (e: Exception) { isConnecting = true }
                    delay(2000)
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF00FF41), background = Color(0xFF050505))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF050505)) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Scanlines Retro-Futuristas
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            for (y in 0..size.height.toInt() step 8) {
                                drawLine(Color(0x0AFFFFFF), Offset(0f, y.toFloat()), Offset(size.width, y.toFloat()), 2f)
                            }
                        }

                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            // Cabecera WatsApp-Stark
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("WING-STARK OS", color = Color(0xFF00F3FF), fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                                    Text(if (isConnecting) "Sincronizando..." else "JARVIS ONLINE", color = if(isConnecting) Color.Yellow else Color(0xFF00FF41), fontSize = 10.sp)
                                }
                                Box(modifier = Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if(isConnecting) Color.Yellow else Color(0xFF00FF41)))
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // Chat de Pagos Espejo
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
    }

    private fun speakPayment(nombre: String, monto: String) {
        if (isTtsReady) tts.speak("Aviso: Pago de $nombre por $monto soles recibido con éxito.", TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "ES")
            tts.setPitch(1.05f)
            isTtsReady = true
        }
    }
}

@Composable
fun ChatBubble(payment: Map<String, String>) {
    val isYape = payment["banco"] == "YAPE"
    val color = if (isYape) Color(0xFF25D366) else Color(0xFF00F3FF)
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .wrapContentWidth(Alignment.Start)
            .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.5.dp, color.copy(alpha = 0.3f), RoundedCornerShape(topStart = 0.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
            .padding(14.dp)
    ) {
        Text(payment["nombre"] ?: "Usuario", color = color, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text("S/ ${payment["monto"]}", color = Color.White, fontSize = 24.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
        Text(payment["banco"] ?: "SISTEMA", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.align(Alignment.End))
    }
}
