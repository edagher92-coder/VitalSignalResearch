package au.com.elied.vitalsignal.wear.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object WearPermissionPolicy {
    private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    private const val READ_OXYGEN_SATURATION =
        "android.permission.health.READ_OXYGEN_SATURATION"
    private const val READ_SKIN_TEMPERATURE =
        "android.permission.health.READ_SKIN_TEMPERATURE"
    private const val READ_ADDITIONAL_HEALTH_DATA =
        "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"

    fun foregroundResearchPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 36) {
            add(READ_HEART_RATE)
            add(READ_OXYGEN_SATURATION)
            add(READ_SKIN_TEMPERATURE)
        } else {
            add(Manifest.permission.BODY_SENSORS)
        }
        add(READ_ADDITIONAL_HEALTH_DATA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun missingForegroundResearchPermissions(context: Context): Array<String> =
        foregroundResearchPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
}
