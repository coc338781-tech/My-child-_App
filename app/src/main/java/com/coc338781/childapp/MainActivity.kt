package com.coc338781.childapp

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : AppCompatActivity() {

    // ========== ඔබේ විස්තර මෙතන දාන්න ==========
    private val BOT_TOKEN = "8968739971:AAEsGJc_-MNGE8KKxqwzaZM3TPinPVg_Bms"
    private val CHAT_ID = "7905408190"
    // ============================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple UI හදමු
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(50, 100, 50, 50)

        val title = TextView(this)
        title.text = "System Service\n\nReady to start"
        title.textSize = 20f
        layout.addView(title)

        val statusText = TextView(this)
        statusText.text = "\n\nStatus: Not tested"
        statusText.textSize = 14f
        layout.addView(statusText)

        val testButton = Button(this)
        testButton.text = "Test Telegram Connection"
        layout.addView(testButton)

        testButton.setOnClickListener {
            statusText.text = "\n\nStatus: Sending..."
            testTelegram(statusText)
        }

        setContentView(layout)
    }

    private fun testTelegram(statusText: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val message = "✅ Child App is alive!\n\nDevice: ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE}"

                val url = "https://api.telegram.org/bot$BOT_TOKEN/sendMessage"
                val json = """{"chat_id":"$CHAT_ID","text":"$message"}"""
                val body = json.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        statusText.text = "\n\nStatus: ✅ SUCCESS!\nCheck Telegram."
                        Toast.makeText(this@MainActivity, "Telegram එකට යැව්වා!", Toast.LENGTH_LONG).show()
                    } else {
                        statusText.text = "\n\nStatus: ❌ Failed\n$responseBody"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "\n\nStatus: ❌ Error\n${e.message}"
                }
            }
        }
    }
}
