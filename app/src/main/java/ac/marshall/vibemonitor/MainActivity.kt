package ac.marshall.vibemonitor

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var monitorService: VibrationMonitorService? = null
    private var isBound = false
    private lateinit var sharedPreferences: SharedPreferences
    private var lastRmsValue: Float = 0.0f

    // UI Elements
    private lateinit var rmsTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var monitorNameEditText: EditText
    private lateinit var thresholdEditText: EditText
    private lateinit var durationEditText: EditText
    private lateinit var setThresholdFromRmsButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var manualStateToggleButton: Button
    private lateinit var testWebhookButton: Button
    private lateinit var webhookUrlsContainer: LinearLayout
    private lateinit var addWebhookButton: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VibrationMonitorService.LocalBinder
            monitorService = binder.getService()
            isBound = true
            // Immediately sync UI with current service state
            updateUiWithCurrentServiceState()
            // Start listening for future updates
            bindServiceData()
            updateUiForServiceState(true)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            monitorService = null
            updateUiForServiceState(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sharedPreferences = getSharedPreferences("VibeMonitorPrefs", Context.MODE_PRIVATE)
        setupUI()
        setupClickListeners()
        // Automatically start service if permissions are already granted and it's not running
        if (!VibrationMonitorService.isRunning && hasNotificationPermission()) {
            startService(Intent(this, VibrationMonitorService::class.java))
        }
    }
    
    override fun onStart() {
        super.onStart()
        // Bind to service if it's running
        if(VibrationMonitorService.isRunning){
            Intent(this, VibrationMonitorService::class.java).also { intent ->
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
    
    private fun setupUI() {
        rmsTextView = findViewById(R.id.rms_textview)
        statusTextView = findViewById(R.id.status_textview)
        monitorNameEditText = findViewById(R.id.monitor_name_edittext)
        thresholdEditText = findViewById(R.id.threshold_edittext)
        durationEditText = findViewById(R.id.duration_edittext)
        setThresholdFromRmsButton = findViewById(R.id.set_threshold_from_rms_button)
        saveSettingsButton = findViewById(R.id.save_settings_button)
        pauseResumeButton = findViewById(R.id.pause_resume_button)
        manualStateToggleButton = findViewById(R.id.manual_state_toggle_button)
        testWebhookButton = findViewById(R.id.test_webhook_button)
        webhookUrlsContainer = findViewById(R.id.webhook_urls_container)
        addWebhookButton = findViewById(R.id.add_webhook_button)

        // Load initial settings to populate fields
        loadSettingsToUI()
        // Set initial UI state
        val isRunning = VibrationMonitorService.isRunning
        manualStateToggleButton.isEnabled = isRunning
        testWebhookButton.isEnabled = isRunning
        pauseResumeButton.isEnabled = isRunning
        pauseResumeButton.text = if (isRunning) "Pause/Resume" else "Service Not Running"
    }

    private fun setupClickListeners() {
        pauseResumeButton.setOnClickListener {
            monitorService?.toggleDetection()
        }

        saveSettingsButton.setOnClickListener {
            saveSettingsFromUI()
            monitorService?.reloadSettings()
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
        }

        manualStateToggleButton.setOnClickListener {
            monitorService?.manualStateToggle()
        }
        
        testWebhookButton.setOnClickListener {
            monitorService?.sendTestWebhook()
        }
        
        addWebhookButton.setOnClickListener {
            addWebhookRow()
        }

        setThresholdFromRmsButton.setOnClickListener {
            thresholdEditText.setText(String.format("%.3f", lastRmsValue))
        }

        // Editor Listeners
        val editorActionListener = { view: View, actionId: Int ->
            handleEditorAction(view, actionId)
        }
        monitorNameEditText.setOnEditorActionListener { v, actionId, _ -> editorActionListener(v, actionId) }
        thresholdEditText.setOnEditorActionListener { v, actionId, _ -> editorActionListener(v, actionId) }
        durationEditText.setOnEditorActionListener { v, actionId, _ -> editorActionListener(v, actionId) }
    }

    private fun handleEditorAction(view: View, actionId: Int): Boolean {
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            saveSettingsButton.performClick()
            view.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
            return true
        }
        return false
    }
    
    private fun bindServiceData() {
        lifecycleScope.launch {
            monitorService?.statusFlow?.collectLatest { status ->
                statusTextView.text = status
            }
        }
        lifecycleScope.launch {
            monitorService?.rmsFlow?.collectLatest { rms ->
                lastRmsValue = rms
                rmsTextView.text = String.format("RMS: %.3f g", rms)
            }
        }
        lifecycleScope.launch {
            monitorService?.isPausedFlow?.collectLatest { isPaused ->
                pauseResumeButton.text = if (isPaused) "Resume Detection" else "Pause Detection"
            }
        }
    }

    private fun updateUiForServiceState(isRunning: Boolean) {
        // This function is no longer the primary driver of UI state.
        // It's kept for the onServiceDisconnected callback.
        manualStateToggleButton.isEnabled = isRunning
        testWebhookButton.isEnabled = isRunning
        pauseResumeButton.isEnabled = isRunning
        pauseResumeButton.text = if (isRunning) "Pause/Resume" else "Service Disconnected"
    }

    private fun updateUiWithCurrentServiceState() {
        monitorService?.let { service ->
            statusTextView.text = service.currentStatus
            lastRmsValue = service.currentRms
            rmsTextView.text = String.format("RMS: %.3f g", service.currentRms)
            pauseResumeButton.text = if (service.isCurrentlyPaused) "Resume Detection" else "Pause Detection"
        }
    }

    // --- Permissions ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startService(Intent(this, VibrationMonitorService::class.java))
            } else {
                Toast.makeText(this, "Notification permission is required to run the monitor.", Toast.LENGTH_LONG).show()
            }
        }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startService(Intent(this, VibrationMonitorService::class.java))
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Explain to the user why you need the permission
                    Toast.makeText(this, "We need to show a notification to keep the monitor running when the app is in the background.", Toast.LENGTH_LONG).show()
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // No permission needed for older Android versions
            startService(Intent(this, VibrationMonitorService::class.java))
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }
    
    private fun loadSettingsToUI() {
        val monitorName = sharedPreferences.getString("monitor_name", "dryer-1") ?: "dryer-1"
        val threshold = sharedPreferences.getFloat("threshold", 0.1f)
        val duration = sharedPreferences.getInt("duration", 30)
        val webhookUrls = sharedPreferences.getStringSet("webhook_urls", setOf("https://vibemonitor.free.beeceptor.com")) ?: setOf("https://vibemonitor.free.beeceptor.com")

        monitorNameEditText.setText(monitorName)
        thresholdEditText.setText(threshold.toString())
        durationEditText.setText(duration.toString())
        
        webhookUrlsContainer.removeAllViews()
        if (webhookUrls.isEmpty()) {
            addWebhookRow()
        } else {
            webhookUrls.forEach { addWebhookRow(it) }
        }
    }

    private fun saveSettingsFromUI() {
        with(sharedPreferences.edit()) {
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
            putStringSet("webhook_urls", newUrls)

            // Save Name
            val monitorName = monitorNameEditText.text.toString().ifBlank { "dryer-1" }
            putString("monitor_name", monitorName)

            // Save Threshold
            val threshold = thresholdEditText.text.toString().toFloatOrNull() ?: 0.1f
            putFloat("threshold", threshold)

            // Save Duration
            val duration = durationEditText.text.toString().toIntOrNull() ?: 30
            putInt("duration", duration)

            apply()
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

        // Row is always editable
        editText.isEnabled = true
        removeButton.isEnabled = true

        webhookUrlsContainer.addView(rowView)
    }
}