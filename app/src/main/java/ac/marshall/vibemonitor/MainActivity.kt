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
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var monitorNameEditText: EditText
    private lateinit var thresholdEditText: EditText
    private lateinit var durationEditText: EditText
    private lateinit var setThresholdFromRmsButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var manualStateToggleButton: Button
    private lateinit var webhookUrlsContainer: LinearLayout
    private lateinit var addWebhookButton: Button

    private lateinit var sharedPreferences: SharedPreferences

    private val accelerationSamples = mutableListOf<Float>()
    private val sampleWindowSize = 250 // Corresponds to 5 seconds if sampling at 50Hz
    private var lastRmsValue: Float = 0.0f

    // Low-pass filter for isolating gravity.
    private val gravity = floatArrayOf(0f, 0f, 0f)
    private val alpha: Float = 0.8f

    private var monitorName: String = "dryer-1"
    private var dryingThreshold: Float = 0.1f // Default threshold, user can override. Lowered for filtered data.
    private var webhookUrls = mutableSetOf<String>()
    private var monitoringDurationSeconds = 30 // Default to 30 seconds
    private var isUserPaused = false

    private enum class MonitorState { IDLE, MONITORING_START, DETECTED, MONITORING_STOP }
    private var currentState = MonitorState.IDLE
    private val stateChangeHandler = Handler(Looper.getMainLooper())
    private var countdownSeconds = 0


    // You can adjust these. SAMPLING_PERIOD_US determines how often you get sensor events.
    // 20000 microseconds = 20ms = 50Hz.
    // MAX_REPORT_LATENCY_US allows the system to batch events, saving power.
    // 1 second = 1,000,000 microseconds.
    private val SAMPLING_PERIOD_US = 20000 
    private val MAX_REPORT_LATENCY_US = 1000000 

    companion object {
        private const val PREFS_NAME = "VibeMonitorPrefs"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_WEBHOOK_URLS = "webhook_urls" // Corrected name
        private const val KEY_DURATION = "duration"
        private const val KEY_NAME = "monitor_name"
        private const val TAG = "VibeMonitor"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Assumes your layout file is activity_main.xml

        rmsTextView = findViewById(R.id.rms_textview)
        statusTextView = findViewById(R.id.status_textview)
        monitorNameEditText = findViewById(R.id.monitor_name_edittext)
        thresholdEditText = findViewById(R.id.threshold_edittext)
        durationEditText = findViewById(R.id.duration_edittext)
        setThresholdFromRmsButton = findViewById(R.id.set_threshold_from_rms_button)
        saveSettingsButton = findViewById(R.id.save_settings_button)
        pauseResumeButton = findViewById(R.id.pause_resume_button)
        manualStateToggleButton = findViewById(R.id.manual_state_toggle_button)
        webhookUrlsContainer = findViewById(R.id.webhook_urls_container)
        addWebhookButton = findViewById(R.id.add_webhook_button)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()

        monitorNameEditText.setText(monitorName)
        thresholdEditText.setText(dryingThreshold.toString())
        durationEditText.setText(monitoringDurationSeconds.toString())

        // --- Improved EditText Handling ---
        monitorNameEditText.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveSettingsButton.performClick()
                v.clearFocus() // Remove focus from EditText
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                true // Handled
            } else {
                false // Not handled
            }
        }
        thresholdEditText.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveSettingsButton.performClick()
                v.clearFocus() // Remove focus from EditText
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                true // Handled
            } else {
                false // Not handled
            }
        }

        saveSettingsButton.setOnClickListener {
            // Save Webhook URLs
            val newUrls = mutableSetOf<String>()
            for (i in 0 until webhookUrlsContainer.childCount) {
                val row = webhookUrlsContainer.getChildAt(i)
                val editText = row.findViewById<EditText>(R.id.webhook_url_edittext_row)
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) {
                    newUrls.add(url)
                }
            }
            webhookUrls = newUrls

            // Save Name
            monitorName = monitorNameEditText.text.toString().ifBlank { "dryer-1" }

            // Save Threshold
            dryingThreshold = thresholdEditText.text.toString().toFloatOrNull() ?: dryingThreshold
            // Save Duration
            monitoringDurationSeconds = durationEditText.text.toString().toIntOrNull() ?: 30

            // Persist all settings
            with(sharedPreferences.edit()) {
                putStringSet(KEY_WEBHOOK_URLS, webhookUrls)
                putString(KEY_NAME, monitorName)
                putFloat(KEY_THRESHOLD, dryingThreshold)
                putInt(KEY_DURATION, monitoringDurationSeconds)
                apply()
            }
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
        }

        setThresholdFromRmsButton.setOnClickListener {
            thresholdEditText.setText(String.format("%.3f", lastRmsValue))
        }

        addWebhookButton.setOnClickListener {
            addWebhookRow()
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
            if (currentState == MonitorState.DETECTED || currentState == MonitorState.MONITORING_STOP) {
                currentState = MonitorState.IDLE
                Log.d(TAG, "State manually changed to IDLE. Sending webhook.")
                sendWebhook(isDetected = false)
                updateStatus("Status: Manually set to Idle.")
            } else { // IDLE or MONITORING_START
                currentState = MonitorState.DETECTED
                Log.d(TAG, "State manually changed to DETECTED. Sending webhook.")
                sendWebhook(isDetected = true)
                updateStatus("Status: Manually set to Detected.")
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
            // Isolate gravity with a low-pass filter.
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

            // Remove the gravity contribution to get the linear acceleration (i.e., vibration).
            val linearAccelerationX = event.values[0] - gravity[0]
            val linearAccelerationY = event.values[1] - gravity[1]
            val linearAccelerationZ = event.values[2] - gravity[2]

            // Calculate the magnitude of the linear acceleration vector
            val magnitude = sqrt((linearAccelerationX * linearAccelerationX + linearAccelerationY * linearAccelerationY + linearAccelerationZ * linearAccelerationZ).toDouble()).toFloat()
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
        monitorName = sharedPreferences.getString(KEY_NAME, "dryer-1") ?: "dryer-1"
        dryingThreshold = sharedPreferences.getFloat(KEY_THRESHOLD, 0.1f)
        webhookUrls = sharedPreferences.getStringSet(KEY_WEBHOOK_URLS, setOf())?.toMutableSet() ?: mutableSetOf()
        monitoringDurationSeconds = sharedPreferences.getInt(KEY_DURATION, 30)

        // Populate the UI
        webhookUrlsContainer.removeAllViews()
        if (webhookUrls.isEmpty()) {
            addWebhookRow() // Start with one empty row if none are saved
        } else {
            webhookUrls.forEach { addWebhookRow(it) }
        }
    }

    private fun addWebhookRow(url: String = "") {
        val inflater = LayoutInflater.from(this)
        val rowView = inflater.inflate(R.layout.webhook_url_row, webhookUrlsContainer, false)

        val editText = rowView.findViewById<EditText>(R.id.webhook_url_edittext_row)
        val removeButton = rowView.findViewById<View>(R.id.remove_webhook_button_row)

        editText.setText(url)
        removeButton.setOnClickListener {
            webhookUrlsContainer.removeView(rowView)
        }

        webhookUrlsContainer.addView(rowView)
    }

    private fun handleStateTransitions(rms: Float) {
        when (currentState) {
            MonitorState.IDLE -> {
                updateStatus("Status: Idle. Waiting for vibration above threshold (%.3f)".format(dryingThreshold))
                if (rms > dryingThreshold) {
                    currentState = MonitorState.MONITORING_START
                    startMonitoringStart()
                }
            }
            MonitorState.MONITORING_START -> {
                // Status is handled by countdown runnable
                if (rms < dryingThreshold) {
                    // Vibration dropped, go back to idle
                    currentState = MonitorState.IDLE
                    cancelMonitoring()
                }
                // else: wait for timer to fire
            }
            MonitorState.DETECTED -> {
                updateStatus("Status: Detected.")
                if (rms < dryingThreshold) {
                    currentState = MonitorState.MONITORING_STOP
                    startMonitoringStop()
                }
            }
            MonitorState.MONITORING_STOP -> {
                // Status is handled by countdown runnable
                if (rms > dryingThreshold) {
                    // Vibration picked up again, go back to drying
                    currentState = MonitorState.DETECTED
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
        stateChangeHandler.postDelayed(monitoringStartRunnable, monitoringDurationSeconds * 1000L)
        // Start countdown
        countdownSeconds = monitoringDurationSeconds
        stateChangeHandler.post(countdownRunnable)
    }

    private fun startMonitoringStop() {
        Log.d(TAG, "Starting to monitor for cycle stop.")
        stateChangeHandler.postDelayed(monitoringStopRunnable, monitoringDurationSeconds * 1000L)
        // Start countdown
        countdownSeconds = monitoringDurationSeconds
        stateChangeHandler.post(countdownRunnable)
    }

    private fun cancelMonitoring() {
        Log.d(TAG, "Cancelling monitoring.")
        stateChangeHandler.removeCallbacks(monitoringStartRunnable)
        stateChangeHandler.removeCallbacks(monitoringStopRunnable)
        // Stop countdown
        stateChangeHandler.removeCallbacks(countdownRunnable)
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdownSeconds > 0) {
                val statusMessage = when (currentState) {
                    MonitorState.MONITORING_START -> "Status: Vibration detected. Confirming start in $countdownSeconds s..."
                    MonitorState.MONITORING_STOP -> "Status: Low vibration. Confirming stop in $countdownSeconds s..."
                    else -> "" // Should not happen
                }
                if (statusMessage.isNotEmpty()) {
                    updateStatus(statusMessage)
                }
                countdownSeconds--
                stateChangeHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val monitoringStartRunnable = Runnable {
        if (currentState == MonitorState.MONITORING_START) {
            currentState = MonitorState.DETECTED
            Log.d(TAG, "State changed to DETECTED. Sending webhook.")
            sendWebhook(isDetected = true)
        }
    }

    private val monitoringStopRunnable = Runnable {
        if (currentState == MonitorState.MONITORING_STOP) {
            currentState = MonitorState.IDLE
            Log.d(TAG, "State changed to IDLE. Sending webhook.")
            sendWebhook(isDetected = false)
        }
    }

    private fun sendWebhook(isDetected: Boolean) {
        if (webhookUrls.isEmpty()) {
            Log.w(TAG, "No Webhook URLs are set. Cannot send data.")
            runOnUiThread {
                Toast.makeText(this, "No Webhook URL set!", Toast.LENGTH_SHORT).show()
            }
            return
        }

        webhookUrls.forEach { urlString ->
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val url = URL(urlString)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connection.doOutput = true
                    connection.connectTimeout = 5000 // 5 seconds
                    connection.readTimeout = 5000 // 5 seconds

                    val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())

                    val jsonObject = JSONObject()
                    jsonObject.put("name", monitorName)
                    jsonObject.put("timestamp", timestamp)
                    jsonObject.put("isDetected", isDetected)
                    val jsonPayload = jsonObject.toString()

                    val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
                    writer.write(jsonPayload)
                    writer.flush()
                    writer.close()

                    val responseCode = connection.responseCode
                    Log.d(TAG, "Webhook to $urlString response code: $responseCode")
                    if (responseCode !in 200..299) {
                        val errorStream = connection.errorStream?.bufferedReader()?.readText()
                        Log.e(TAG, "Webhook to $urlString failed with code $responseCode: $errorStream")
                    }

                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending webhook to $urlString", e)
                }
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