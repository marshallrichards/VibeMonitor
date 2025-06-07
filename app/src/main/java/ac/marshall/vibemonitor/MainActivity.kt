package ac.marshall.vibemonitor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var rmsTextView: TextView

    private val accelerationSamples = mutableListOf<Float>()
    private val sampleWindowSize = 250 // Corresponds to 5 seconds if sampling at 50Hz
    private var currentSampleCount = 0

    // You can adjust these. SAMPLING_PERIOD_US determines how often you get sensor events.
    // 20000 microseconds = 20ms = 50Hz.
    // MAX_REPORT_LATENCY_US allows the system to batch events, saving power.
    // 1 second = 1,000,000 microseconds.
    private val SAMPLING_PERIOD_US = 20000 
    private val MAX_REPORT_LATENCY_US = 1000000 


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Assumes your layout file is activity_main.xml

        rmsTextView = findViewById(R.id.rms_textview)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            rmsTextView.text = "Accelerometer not available"
            // Handle the case where the accelerometer is not available
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            // SENSOR_DELAY_NORMAL is another option, but for vibration, more frequent updates are better.
            // We are using SAMPLING_PERIOD_US and MAX_REPORT_LATENCY_US for more control
            sensorManager.registerListener(this, it, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
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
                rmsTextView.text = String.format("RMS: %.2f g", rms)
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
}