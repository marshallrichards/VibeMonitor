package ac.marshall.vibemonitor

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var rmsTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var webhookUrlEditText: EditText
    private lateinit var thresholdEditText: EditText
    private lateinit var setThresholdFromRmsButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var manualStateToggleButton: Button

    private lateinit var sharedPreferences: SharedPreferences

    private val accelerationSamples = mutableListOf<Float>()
    private val sampleWindowSize = 250 // Corresponds to 5 seconds if sampling at 50Hz
    private var lastRmsValue: Float = 0.0f

    private var dryingThreshold: Float = 0.5f // Default threshold, user can override
    private var webhookUrl: String = ""
    private var isUserPaused = false

    private enum class DryerState { IDLE, MONITORING_START, DRYING, MONITORING_STOP }
    private var currentState = DryerState.IDLE
    private val stateChangeHandler = Handler(Looper.getMainLooper())


    // You can adjust these. SAMPLING_PERIOD_US determines how often you get sensor events.
    // 20000 microseconds = 20ms = 50Hz.
    // MAX_REPORT_LATENCY_US allows the system to batch events, saving power.
    // 1 second = 1,000,000 microseconds.
    private val SAMPLING_PERIOD_US = 20000 
    private val MAX_REPORT_LATENCY_US = 1000000 

    companion object {
        private const val MONITORING_DURATION_MS = 30000L // 30 seconds
        private const val PREFS_NAME = "VibeMonitorPrefs"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val TAG = "VibeMonitor"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Assumes your layout file is activity_main.xml

        rmsTextView = findViewById(R.id.rms_textview)
        statusTextView = findViewById(R.id.status_textview)
        webhookUrlEditText = findViewById(R.id.webhook_url_edittext)
        thresholdEditText = findViewById(R.id.threshold_edittext)
        setThresholdFromRmsButton = findViewById(R.id.set_threshold_from_rms_button)
        saveSettingsButton = findViewById(R.id.save_settings_button)
        pauseResumeButton = findViewById(R.id.pause_resume_button)
        manualStateToggleButton = findViewById(R.id.manual_state_toggle_button)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()

        webhookUrlEditText.setText(webhookUrl)
        thresholdEditText.setText(dryingThreshold.toString())

        saveSettingsButton.setOnClickListener {
            webhookUrl = webhookUrlEditText.text.toString()
            dryingThreshold = thresholdEditText.text.toString().toFloatOrNull() ?: dryingThreshold
            with(sharedPreferences.edit()) {
                putString(KEY_WEBHOOK_URL, webhookUrl)
                putFloat(KEY_THRESHOLD, dryingThreshold)
                apply()
            }
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
        }

        setThresholdFromRmsButton.setOnClickListener {
            thresholdEditText.setText(String.format("%.3f", lastRmsValue))
        }

        pauseResumeButton.setOnClickListener {
            isUserPaused = !isUserPaused
            if (isUserPaused) {
                cancelMonitoring() // Stop any pending state changes
                pauseResumeButton.text = "Resume Monitoring"
                updateStatus("Status: Paused by user.")
                Log.d(TAG, "State machine paused by user.")
            } else {
                pauseResumeButton.text = "Pause Monitoring"
                Log.d(TAG, "State machine resumed by user.")
                // Status will update on the next sensor event via handleStateTransitions
            }
        }

        manualStateToggleButton.setOnClickListener {
            cancelMonitoring() // Stop any pending state changes
            if (currentState == DryerState.DRYING || currentState == DryerState.MONITORING_STOP) {
                currentState = DryerState.IDLE
                Log.d(TAG, "State manually changed to IDLE. Sending webhook.")
                sendWebhook(isDrying = false)
                updateStatus("Status: Manually set to Idle.")
            } else { // IDLE or MONITORING_START
                currentState = DryerState.DRYING
                Log.d(TAG, "State manually changed to DRYING. Sending webhook.")
                sendWebhook(isDrying = true)
                updateStatus("Status: Manually set to Drying.")
            }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            rmsTextView.text = "Accelerometer not available"
            // Handle the case where the accelerometer is not available
        }
    }

    override fun onResume() {
        super.onResume()
        resumeSensorListener()
        // Sync button text with the state
        if (isUserPaused) {
            pauseResumeButton.text = "Resume Monitoring"
            updateStatus("Status: Paused by user.")
        } else {
            pauseResumeButton.text = "Pause Monitoring"
        }
    }

    override fun onPause() {
        super.onPause()
        pauseSensorListener()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Can be ignored for this example
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Calculate the magnitude of the acceleration vector
            val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            addSample(magnitude)

            if (accelerationSamples.size >= sampleWindowSize) {
                val rms = calculateRMS()
                lastRmsValue = rms
                // Update UI in real-time
                rmsTextView.text = String.format("RMS: %.3f g", rms)

                if (!isUserPaused) {
                    // State machine logic is only active if not paused by user
                    handleStateTransitions(rms)
                }
            } else {
                rmsTextView.text = "RMS: Collecting samples (${accelerationSamples.size}/${sampleWindowSize})..."
            }
        }
    }

    private fun addSample(magnitude: Float) {
        if (accelerationSamples.size >= sampleWindowSize) {
            accelerationSamples.removeAt(0) // Remove oldest sample
        }
        accelerationSamples.add(magnitude)
    }

    private fun calculateRMS(): Float {
        if (accelerationSamples.isEmpty()) return 0.0f
        var sumOfSquares = 0.0
        for (sample in accelerationSamples) {
            sumOfSquares += (sample * sample).toDouble()
        }
        return sqrt(sumOfSquares / accelerationSamples.size).toFloat()
    }

    private fun loadSettings() {
        dryingThreshold = sharedPreferences.getFloat(KEY_THRESHOLD, 0.5f)
        webhookUrl = sharedPreferences.getString(KEY_WEBHOOK_URL, "") ?: ""
    }

    private fun handleStateTransitions(rms: Float) {
        when (currentState) {
            DryerState.IDLE -> {
                updateStatus("Status: Idle. Waiting for vibration above threshold (%.3f)".format(dryingThreshold))
                if (rms > dryingThreshold) {
                    currentState = DryerState.MONITORING_START
                    startMonitoringStart()
                }
            }
            DryerState.MONITORING_START -> {
                updateStatus("Status: Vibration detected. Confirming start...")
                if (rms < dryingThreshold) {
                    // Vibration dropped, go back to idle
                    currentState = DryerState.IDLE
                    cancelMonitoring()
                }
                // else: wait for timer to fire
            }
            DryerState.DRYING -> {
                updateStatus("Status: Drying in progress.")
                if (rms < dryingThreshold) {
                    currentState = DryerState.MONITORING_STOP
                    startMonitoringStop()
                }
            }
            DryerState.MONITORING_STOP -> {
                updateStatus("Status: Low vibration. Confirming stop...")
                if (rms > dryingThreshold) {
                    // Vibration picked up again, go back to drying
                    currentState = DryerState.DRYING
                    cancelMonitoring()
                }
                // else: wait for timer to fire
            }
        }
    }

    private fun updateStatus(message: String) {
        statusTextView.text = message
    }

    private fun startMonitoringStart() {
        Log.d(TAG, "Starting to monitor for cycle start.")
        stateChangeHandler.postDelayed(monitoringStartRunnable, MONITORING_DURATION_MS)
    }

    private fun startMonitoringStop() {
        Log.d(TAG, "Starting to monitor for cycle stop.")
        stateChangeHandler.postDelayed(monitoringStopRunnable, MONITORING_DURATION_MS)
    }

    private fun cancelMonitoring() {
        Log.d(TAG, "Cancelling monitoring.")
        stateChangeHandler.removeCallbacks(monitoringStartRunnable)
        stateChangeHandler.removeCallbacks(monitoringStopRunnable)
    }

    private val monitoringStartRunnable = Runnable {
        if (currentState == DryerState.MONITORING_START) {
            currentState = DryerState.DRYING
            Log.d(TAG, "State changed to DRYING. Sending webhook.")
            sendWebhook(isDrying = true)
        }
    }

    private val monitoringStopRunnable = Runnable {
        if (currentState == DryerState.MONITORING_STOP) {
            currentState = DryerState.IDLE
            Log.d(TAG, "State changed to IDLE. Sending webhook.")
            sendWebhook(isDrying = false)
        }
    }

    private fun sendWebhook(isDrying: Boolean) {
        if (webhookUrl.isBlank()) {
            Log.w(TAG, "Webhook URL is not set. Cannot send data.")
            runOnUiThread {
                Toast.makeText(this, "Webhook URL not set!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL(webhookUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.doOutput = true
                connection.connectTimeout = 5000 // 5 seconds
                connection.readTimeout = 5000 // 5 seconds

                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())

                val jsonObject = JSONObject()
                jsonObject.put("timestamp", timestamp)
                jsonObject.put("isDrying", isDrying)
                val jsonPayload = jsonObject.toString()

                val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
                writer.write(jsonPayload)
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                Log.d(TAG, "Webhook response code: $responseCode")
                if (responseCode !in 200..299) {
                    val errorStream = connection.errorStream?.bufferedReader()?.readText()
                    Log.e(TAG, "Webhook failed with code $responseCode: $errorStream")
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending webhook", e)
            }
        }
    }

    private fun pauseSensorListener() {
        sensorManager.unregisterListener(this)
        cancelMonitoring()
        Log.d(TAG, "Sensor listener paused.")
    }

    private fun resumeSensorListener() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US)
            Log.d(TAG, "Sensor listener resumed.")
            // status will update on next sensor event
        }
    }
}