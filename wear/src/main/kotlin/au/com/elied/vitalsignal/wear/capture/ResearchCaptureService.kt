package au.com.elied.vitalsignal.wear.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.com.elied.vitalsignal.wear.MainActivity
import au.com.elied.vitalsignal.wear.R
import au.com.elied.vitalsignal.wear.sensor.SensorCatalog
import au.com.elied.vitalsignal.wear.sensor.WatchDataChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ResearchCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> startCapture(intent.toCaptureConfig())
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        captureJob?.cancel()
        serviceScope.launch {
            try {
                ResearchCaptureRuntime.controller.stop()
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    private fun startCapture(config: ResearchCaptureConfig) {
        startAsHealthForegroundService(
            buildNotification(getString(R.string.capture_notification_starting)),
        )
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            ResearchCaptureRuntime.controller.start(config)
            when (ResearchCaptureRuntime.controller.status.value.phase) {
                CapturePhase.ACTIVE -> {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(
                        NOTIFICATION_ID,
                        buildNotification(getString(R.string.capture_notification_active)),
                    )
                    delay(config.plannedDurationSeconds * 1_000L)
                    ResearchCaptureRuntime.controller.stop()
                    ServiceCompat.stopForeground(
                        this@ResearchCaptureService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf()
                }

                CapturePhase.BLOCKED,
                CapturePhase.ERROR,
                -> {
                    ServiceCompat.stopForeground(
                        this@ResearchCaptureService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                    stopSelf()
                }

                else -> Unit
            }
        }
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            ResearchCaptureRuntime.controller.stop()
            ServiceCompat.stopForeground(
                this@ResearchCaptureService,
                ServiceCompat.STOP_FOREGROUND_REMOVE,
            )
            stopSelf()
        }
    }

    private fun startAsHealthForegroundService(notification: Notification) {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.capture_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopCapture = PendingIntent.getService(
            this,
            1,
            Intent(this, ResearchCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vital_signal)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.capture_notification_stop), stopCapture)
            .build()
    }

    private fun Intent.toCaptureConfig(): ResearchCaptureConfig {
        val channels = getStringExtra(EXTRA_CHANNELS)
            ?.split(',')
            ?.mapNotNull { encoded ->
                WatchDataChannel.entries.firstOrNull { it.name == encoded }
            }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: SensorCatalog.researchDefaults

        return ResearchCaptureConfig(
            sessionId = getStringExtra(EXTRA_SESSION_ID).orEmpty().ifBlank {
                ResearchCaptureConfig.newPilotSession().sessionId
            },
            channels = channels,
            plannedDurationSeconds = getIntExtra(EXTRA_DURATION_SECONDS, 20 * 60),
        )
    }

    companion object {
        private const val ACTION_START = "au.com.elied.vitalsignal.action.START_RESEARCH_CAPTURE"
        private const val ACTION_STOP = "au.com.elied.vitalsignal.action.STOP_RESEARCH_CAPTURE"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_CHANNELS = "channels"
        private const val EXTRA_DURATION_SECONDS = "duration_seconds"
        private const val CHANNEL_ID = "research_capture"
        private const val NOTIFICATION_ID = 4102

        fun start(context: Context, config: ResearchCaptureConfig) {
            val intent = Intent(context, ResearchCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, config.sessionId)
                putExtra(EXTRA_CHANNELS, config.channels.joinToString(",", transform = { it.name }))
                putExtra(EXTRA_DURATION_SECONDS, config.plannedDurationSeconds)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ResearchCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
