package de.matthiasemde.gaggiaiot

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.math.round

data class GaggiaState (
    val temp: Double
)

private val client = OkHttpClient()

suspend fun getState(): GaggiaState {
    val request = Request.Builder()
        .url("http://gaggia-iot/info/sensors")
        .build()

    val result = suspendCancellableCoroutine<GaggiaState> { cancellableContinuation ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")

                    cancellableContinuation.resumeWith(
                        Result.success(GaggiaState(
                            ObjectMapper().readTree(response.body!!.string()).get("temperature").asDouble()
                        ))
                    )
                }
            }
        })
    }
    return result
}
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val realTempView = findViewById<TextView>(R.id.realTemp)

        lifecycleScope.launch {
            while(true) {
                val state = getState()
                realTempView.text = "${round(state.temp)}°C"
                delay(1000)
            }
        }
        Log.d("Test", "is this reached?")
    }
}