package app.mettle.tracking

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What this phone can do; decides which tracking methods Mettle uses. */
object Capabilities {
    fun detect(context: Context): JsonObject {
        val pm = context.packageManager
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val stepCounter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val gnss = if (Build.VERSION.SDK_INT >= 30) lm.gnssCapabilities else null
        return buildJsonObject {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("android", Build.VERSION.SDK_INT)
            put("barometer", sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null)
            put("step_counter", stepCounter != null)
            put("step_fifo", stepCounter?.fifoMaxEventCount ?: 0)
            put("accelerometer", sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null)
            put("gyroscope", sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null)
            put("magnetometer", sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null)
            put("significant_motion", sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null)
            put("wifi_rtt", pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
            put("gnss_raw", gnss?.hasMeasurements() ?: false)
            put("gnss_model", if (Build.VERSION.SDK_INT >= 28) lm.gnssHardwareModelName ?: "" else "")
            put("camera_flash", pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH))
            put("bluetooth_le", pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            put("uwb", Build.VERSION.SDK_INT >= 31 && pm.hasSystemFeature("android.hardware.uwb"))
            put("health_connect", Build.VERSION.SDK_INT >= 34 || runCatching { pm.getPackageInfo("com.google.android.apps.healthdata", 0) }.isSuccess)
        }
    }
}
