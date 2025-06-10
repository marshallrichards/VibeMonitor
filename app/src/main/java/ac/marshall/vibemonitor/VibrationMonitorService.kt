package ac.marshall.vibemonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class VibrationMonitorService : Service(), SensorEventListener {

    inner class LocalBinder : Binder() {
        fun getService(): VibrationMonitorService = this@VibrationMonitorService
    }

    private val binder = LocalBinder()
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var sharedPreferences: SharedPreferences

    // Configuration
    private var monitorName: String = "dryer-1"
    private var dryingThreshold: Float = 0.1f
    private var webhookUrls = mutableSetOf<String>()
    private var monitoringDurationSeconds = 30
    private var isDetectionPaused = false

    // State Machine
    private enum class MonitorState { IDLE, MONITORING_START, DETECTED, MONITORING_STOP }
    private var currentState = MonitorState.IDLE
    private val stateChangeHandler = Handler(Looper.getMainLooper())
    private var countdownJob: Job? = null

    // Sensor Data
    private val accelerationSamples = mutableListOf<Float>()
    private val sampleWindowSize = 75 // Corresponds to 1.5 seconds at 50Hz (SENSOR_DELAY_GAME)
    private val gravity = floatArrayOf(0f, 0f, 0f)
    private val alpha: Float = 0.8f

    // Flows for UI updates
    private val _statusFlow = MutableStateFlow("Service not running.")
    val statusFlow = _statusFlow.asStateFlow()

    private val _rmsFlow = MutableStateFlow(0.0f)
    val rmsFlow = _rmsFlow.asStateFlow()

    private val _isPausedFlow = MutableStateFlow(false)
    val isPausedFlow = _isPausedFlow.asStateFlow()

    companion object {
        private const val PREFS_NAME = "VibeMonitorPrefs"
        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_WEBHOOK_URLS = "webhook_urls"
        private const val KEY_DURATION = "duration"
        private const val KEY_NAME = "monitor_name"
        private const val TAG = "VibeMonitorService"
        private const val NOTIFICATION_CHANNEL_ID = "VibrationMonitorChannel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Monitoring for vibration...")
        startForeground(NOTIFICATION_ID, notification)
        loadSettings()
        registerSensorListener()
        Log.d(TAG, "Service started and is now in foreground.")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensorListener()
        cancelMonitoring()
        Log.d(TAG, "Service destroyed.")
    }

    // --- Public methods for Activity to call ---
    fun toggleDetection() {
        isDetectionPaused = !isDetectionPaused
        _isPausedFlow.value = isDetectionPaused
        if (isDetectionPaused) {
            cancelMonitoring()
            updateStatus("Status: Detection Paused.")
        } else {
            // Status will be updated by handleStateTransitions on the next sensor event.
        }
        Log.d(TAG, "Detection paused state is now: $isDetectionPaused")
    }

    fun reloadSettings() {
        loadSettings()
        Log.d(TAG, "Settings reloaded.")
    }

    fun manualStateToggle() {
        if (isDetectionPaused) {
            // To avoid confusion, don't allow manual toggle while paused.
            // Or, we could decide to un-pause it. For now, let's just log it.
            Log.d(TAG, "Manual toggle ignored while detection is paused.")
            return
        }
        cancelMonitoring()
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

    // --- Sensor and State Logic ---
    private fun loadSettings() {
        monitorName = sharedPreferences.getString(KEY_NAME, "dryer-1") ?: "dryer-1"
        dryingThreshold = sharedPreferences.getFloat(KEY_THRESHOLD, 0.1f)
        webhookUrls = sharedPreferences.getStringSet(KEY_WEBHOOK_URLS, setOf("https://vibemonitor.free.beeceptor.com"))?.toMutableSet() ?: mutableSetOf("https://vibemonitor.free.beeceptor.com")
        monitoringDurationSeconds = sharedPreferences.getInt(KEY_DURATION, 30)
    }

    private fun registerSensorListener() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun unregisterSensorListener() {
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* Not used */ }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

            val linearAccelerationX = event.values[0] - gravity[0]
            val linearAccelerationY = event.values[1] - gravity[1]
            val linearAccelerationZ = event.values[2] - gravity[2]

            val magnitude = sqrt((linearAccelerationX * linearAccelerationX + linearAccelerationY * linearAccelerationY + linearAccelerationZ * linearAccelerationZ).toDouble()).toFloat()
            
            if (accelerationSamples.size >= sampleWindowSize) {
                accelerationSamples.removeAt(0)
            }
            accelerationSamples.add(magnitude)

            if (accelerationSamples.size >= sampleWindowSize) {
                val rms = calculateRMS()
                _rmsFlow.value = rms
                if (!isDetectionPaused) {
                    handleStateTransitions(rms)
                }
            }
        }
    }

    private fun calculateRMS(): Float {
        if (accelerationSamples.isEmpty()) return 0.0f
        var sumOfSquares = 0.0
        for (sample in accelerationSamples) {
            sumOfSquares += (sample * sample).toDouble()
        }
        return sqrt(sumOfSquares / accelerationSamples.size).toFloat()
    }

    private fun handleStateTransitions(rms: Float) {
        // Don't update status if a countdown is active
        if (countdownJob?.isActive == true) return

        when (currentState) {
            MonitorState.IDLE -> {
                updateStatus("Status: Idle. Waiting for vibration...")
                if (rms > dryingThreshold) {
                    currentState = MonitorState.MONITORING_START
                    startMonitoringCountdown(isStarting = true)
                }
            }
            MonitorState.DETECTED -> {
                updateStatus("Status: Detected.")
                if (rms < dryingThreshold) {
                    currentState = MonitorState.MONITORING_STOP
                    startMonitoringCountdown(isStarting = false)
                }
            }
            else -> { /* States are handled by countdown */ }
        }
    }

    private fun startMonitoringCountdown(isStarting: Boolean) {
        countdownJob?.cancel()
        countdownJob = GlobalScope.launch(Dispatchers.Default) {
            try {
                // Countdown loop
                for (i in monitoringDurationSeconds downTo 1) {
                    val status = if (isStarting) {
                        "Status: Vibration detected. Confirming start in $i s..."
                    } else {
                        "Status: Low vibration. Confirming stop in $i s..."
                    }
                    updateStatus(status)
                    kotlinx.coroutines.delay(1000L)
                }

                // Countdown finished, confirm state change
                if (isStarting) {
                    currentState = MonitorState.DETECTED
                    Log.d(TAG, "State changed to DETECTED. Sending webhook.")
                    sendWebhook(isDetected = true)
                } else {
                    currentState = MonitorState.IDLE
                    Log.d(TAG, "State changed to IDLE. Sending webhook.")
                    sendWebhook(isDetected = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Countdown cancelled.")
                // Revert to previous stable state
                currentState = if (isStarting) MonitorState.IDLE else MonitorState.DETECTED
            }
        }
    }

    private fun cancelMonitoring() {
        countdownJob?.cancel()
    }

    // --- Webhook and Notification Logic ---
    private fun sendWebhook(isDetected: Boolean) {
        if (webhookUrls.isEmpty()) {
            Log.w(TAG, "No Webhook URLs are set.")
            return
        }
        webhookUrls.forEach { urlString ->
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val url = URL(urlString)
                    (url.openConnection() as? HttpURLConnection)?.run {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        doOutput = true
                        connectTimeout = 5000
                        readTimeout = 5000

                        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date())
                        val jsonObject = JSONObject().apply {
                            put("name", monitorName)
                            put("timestamp", timestamp)
                            put("isDetected", isDetected)
                        }
                        val jsonPayload = jsonObject.toString()

                        OutputStreamWriter(outputStream, "UTF-8").use {
                            it.write(jsonPayload)
                            it.flush()
                        }
                        Log.d(TAG, "Webhook to $urlString response code: $responseCode")
                        disconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending webhook to $urlString", e)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Vibration Monitor Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE)
            }
        
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Vibe Monitor")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // You might need to provide a real icon
            .setContentIntent(pendingIntent)
            .build()
    }
    
    private fun updateStatus(message: String) {
        _statusFlow.value = message
        // Update the persistent notification as well
        val notification = createNotification(message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
} 