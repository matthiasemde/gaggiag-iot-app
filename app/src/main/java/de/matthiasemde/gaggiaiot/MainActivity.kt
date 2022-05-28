package de.matthiasemde.gaggiaiot

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
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

data class Temperatures (
    val brew: Double,
    val steam: Double,
)

data class Pressures (
    val brew: Double,
    val preinfusion: Double,
)

data class Configuration (
    val temps: Temperatures,
    val pressures: Pressures,
    val preinfusionTime: Int,
)

private val client = OkHttpClient()

suspend fun fetchSensorValues(): GaggiaState {
    val request = Request.Builder()
        .url("http://gaggia-iot/info/sensors")
        .build()

    return suspendCancellableCoroutine { cancellableContinuation ->
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
}

suspend fun fetchConfiguration(): Configuration {
    val request = Request.Builder()
        .url("http://gaggia-iot/configuration")
        .build()

    return suspendCancellableCoroutine { cancellableContinuation ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) throw IOException("Unexpected code $response")
                    val responseBody = ObjectMapper().readTree(response.body!!.string())
                    Log.d("configuration", responseBody.toPrettyString())
                    cancellableContinuation.resumeWith(
                        Result.success(
                            Configuration(
                            responseBody.get("temps")
                                .let { Temperatures(it.get("brew").asDouble(), it.get("steam").asDouble()) },
                            responseBody.get("pressures")
                                .let { Pressures(it.get("brew").asDouble(), it.get("preinfusion").asDouble()) },
                            responseBody.get("preinfusionTime").asInt()
                        ))
                    )
                }
            }
        })
    }
}

fun postTempTarget(newTarget: Double, store: String? = null) {
    val request = Request.Builder().apply {
        if (store.isNullOrEmpty()) {
            url("http://gaggia-iot/direct-control/temperature")
            post(
                FormBody.Builder()
                    .add("newTarget", newTarget.toString())
                    .build()
            )
        } else {
            url("http://gaggia-iot/configuration/temperature")
            post(
                FormBody.Builder()
                    .add(store, newTarget.toString())
                    .build()
            )
        }
    }.build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            e.printStackTrace()
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
            }
        }
    })
}


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val realTempView = findViewById<TextView>(R.id.realTemp)

        lifecycleScope.launch {
            while(true) {
                val state = fetchSensorValues()
                Log.d("state", state.toString())
                realTempView.text = "${round(state.temp)}°C"
                delay(1000)
            }
        }

        var tempTarget = 25.0
        val tempTargetView = findViewById<TextView>(R.id.targetTemp)


        lifecycleScope.launch {
            val config = fetchConfiguration()
            Log.d("config", config.toString())
            tempTarget = config.temps.brew
            tempTargetView.text = "${round(tempTarget)}°C"
        }

        val tempPlusOne = findViewById<Button>(R.id.temperaturePlusOne)
        val tempMinusOne = findViewById<Button>(R.id.temperatureMinusOne)

        tempPlusOne.setOnClickListener {
            tempTarget += 1
            tempTargetView.text = "${round(tempTarget)}°C"
            postTempTarget(tempTarget)
        }
        tempMinusOne.setOnClickListener {
            tempTarget -= 1
            tempTargetView.text = "${round(tempTarget)}°C"
            postTempTarget(tempTarget)
        }

        val storeAsSteam = findViewById<Button>(R.id.storeAsSteam)
        val storeAsBrew = findViewById<Button>(R.id.storeAsBrew)

        storeAsSteam.setOnClickListener {
            postTempTarget(tempTarget, "steam")
        }
        storeAsBrew.setOnClickListener {
            postTempTarget(tempTarget, "brew")
        }
    }
}