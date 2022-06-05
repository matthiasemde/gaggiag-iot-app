package de.matthiasemde.gaggiaiot

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.lifecycle.lifecycleScope
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.NonCancellable.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class GaggiaState (
    val temp: Double?
)

data class Temperatures (
    var brew: Double,
    var steam: Double,
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

private val client = OkHttpClient.Builder()
    .connectTimeout(2, TimeUnit.SECONDS)
    .build()

suspend fun fetchSensorValues(): GaggiaState? {
    val request = Request.Builder()
        .url("http://gaggia-iot/info/sensors")
        .build()

    return suspendCancellableCoroutine { cancellableContinuation ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d("MainActivity", "Failed to fetch sensor values")
                cancellableContinuation.resumeWith(Result.success(null))
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

suspend fun fetchConfiguration(): Configuration? {
    val request = Request.Builder()
        .url("http://gaggia-iot/configuration")
        .build()

    return suspendCancellableCoroutine { cancellableContinuation ->
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancellableContinuation.resumeWith(Result.success(null))
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
            url("http://gaggia-iot/direct-control")
            post(
                FormBody.Builder()
                    .add("temperature", newTarget.toString())
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

fun postSolenoidState(newState: Boolean) {
    val request = Request.Builder()
        .url("http://gaggia-iot/direct-control")
        .post(
            FormBody.Builder()
                .add("solenoid", if(newState) "open" else "close")
                .build()
        )
        .build()

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

        val solenoidControlView = findViewById<SwitchCompat>(R.id.solenoid_control)

        solenoidControlView.setOnCheckedChangeListener { _, isChecked ->
            postSolenoidState(isChecked)
        }

        val realTempView = findViewById<TextView>(R.id.realTemp)

        var showRealTemp = 0

        lifecycleScope.launch {
            while(true) {
                val state = try {
                    fetchSensorValues()
                } catch (e: Exception) {
                    Log.d("MainActivity", "Exception caughht")
                    null
                }
                Log.d("state", state.toString())
                if(showRealTemp == 0) {
                    realTempView.text = state?.temp?.let { String.format("%.1f°C", it) } ?: "--.-°C"
                } else {
                    showRealTemp -= 1
                }
                delay(1000)
            }
        }

        var tempTarget: Double? = 25.0

        var config: Configuration? = null

        lifecycleScope.launch {
            config = fetchConfiguration()
            Log.d("config", config.toString())
            tempTarget = config?.temps?.brew
        }

        val tempPlusOne = findViewById<Button>(R.id.temperaturePlusOne)
        val tempMinusOne = findViewById<Button>(R.id.temperatureMinusOne)

        fun showTargetTemp() {
            showRealTemp = 1
            realTempView.text = String.format("%.1f°C", tempTarget)
        }

        tempPlusOne.setOnClickListener { _ ->
            lifecycleScope.launch {
                if(tempTarget == null) {
                    config?:fetchConfiguration()?.temps?.brew?.let { tempTarget = it }
                }
                tempTarget?.let {
                    tempTarget = it.plus(1.0)
                    postTempTarget(it)
                }
                showTargetTemp()
            }
        }
        tempMinusOne.setOnClickListener { _ ->
            lifecycleScope.launch {
                if (tempTarget == null) {
                    config ?: fetchConfiguration()?.temps?.brew?.let { tempTarget = it }
                }
                tempTarget?.let {
                    tempTarget = it.minus(1.0)
                    postTempTarget(it)
                }
                showTargetTemp()
            }
        }

        val brewTempButtonView = findViewById<Button>(R.id.brewTemp)
        val steamTempButtonView = findViewById<Button>(R.id.steamTemp)

        val vibrationManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager

        brewTempButtonView.setOnClickListener {
            config?.temps?.brew?.let {
                tempTarget = it
                postTempTarget(it)
            }
            showTargetTemp()
        }
        brewTempButtonView.setOnLongClickListener { _ ->
            vibrationManager.defaultVibrator.apply {
                cancel()
                vibrate(
                    VibrationEffect.createOneShot(200,100)
                )
            }
            tempTarget?.let {
                postTempTarget(it, "brew")
                config?.temps?.brew = it
            }
            true
        }
        steamTempButtonView.setOnClickListener {
            config?.temps?.steam?.let {
                Log.d("steamTemp", it.toString())
                tempTarget = it
                postTempTarget(it)
            }
            showTargetTemp()
        }
        steamTempButtonView.setOnLongClickListener { _ ->
            vibrationManager.defaultVibrator.apply {
                cancel()
                vibrate(
                    VibrationEffect.createOneShot(200,100)
                )
            }
            tempTarget?.let {
                postTempTarget(it, "steam")
                config?.temps?.steam = it
            }
            true
        }
    }
}