package com.greecewatch.mob

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class WatchState(
    val armed: Boolean = false,
    val alertActive: Boolean = false,
    val depthCm: Float = 0f,
    val sensorMissing: Boolean = false,
    val status: String = "",
)

/**
 * Foreground service that watches the barometer while armed and, on submersion,
 * posts a man-overboard alert followed by GPS position updates.
 */
class OverboardService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "OverboardService"
        const val ACTION_ARM = "com.greecewatch.mob.ARM"
        const val ACTION_DISARM = "com.greecewatch.mob.DISARM"
        const val ACTION_TRIGGER = "com.greecewatch.mob.TRIGGER"
        const val ACTION_SAFE = "com.greecewatch.mob.SAFE"
        const val ACTION_TEST = "com.greecewatch.mob.TEST"

        private const val CHANNEL_ARMED = "armed"
        private const val CHANNEL_ALERT = "alert"
        private const val NOTIFICATION_ID = 1

        private const val POSITION_INTERVAL_MS = 30_000L
        private const val POSITION_UPDATES_FOR_MS = 60 * 60_000L

        private val _state = MutableStateFlow(WatchState())
        val state: StateFlow<WatchState> = _state

        fun send(context: Context, action: String) {
            val intent = Intent(context, OverboardService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sender = AlertSender()
    private val detector = SubmersionDetector()
    private val location by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private val prefs by lazy { getSharedPreferences("mob", MODE_PRIVATE) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var alertJob: Job? = null
    private var eventId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ARMED, "Overboard watch armed", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Overboard alert", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after killing the process.
        val action = intent?.action ?: if (prefs.getBoolean("armed", false)) ACTION_ARM else ACTION_DISARM
        if (!goForeground()) return START_NOT_STICKY

        when (action) {
            ACTION_ARM -> arm()
            ACTION_DISARM -> disarm()
            ACTION_TRIGGER -> trigger(manual = true)
            ACTION_SAFE -> imSafe()
            ACTION_TEST -> sendTest()
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: Exception) {
        // Android refuses location foreground services started from the background
        // (e.g. after a restart). The user has to open the app and arm again.
        Log.w(TAG, "could not start foreground", e)
        prefs.edit().putBoolean("armed", false).apply()
        _state.update { it.copy(armed = false) }
        stopSelf()
        false
    }

    @SuppressLint("WakelockTimeout") // Held deliberately for as long as the watch is armed.
    private fun arm() {
        val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressure == null) {
            _state.update { it.copy(sensorMissing = true, status = "No barometer on this watch") }
            stopIfIdle()
            return
        }
        if (!_state.value.armed) {
            sensorManager.registerListener(this, pressure, 100_000)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GreeceWatch:armed")
                .apply { acquire() }
        }
        prefs.edit().putBoolean("armed", true).apply()
        _state.update { it.copy(armed = true, status = if (sender.isConfigured) "Armed" else "Armed, but relay NOT configured") }
        refreshNotification()
    }

    private fun disarm() {
        sensorManager.unregisterListener(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        prefs.edit().putBoolean("armed", false).apply()
        _state.update { it.copy(armed = false, depthCm = 0f, status = "Disarmed") }
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (!_state.value.armed && alertJob?.isActive != true) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            refreshNotification()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowMs = SystemClock.elapsedRealtime()
        val fired = detector.onSample(event.values[0], nowMs)
        _state.update { it.copy(depthCm = detector.deltaHpa.coerceAtLeast(0f)) }
        if (fired) trigger(manual = false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun trigger(manual: Boolean) {
        if (alertJob?.isActive == true) return
        val id = UUID.randomUUID().toString()
        eventId = id
        vibrate()
        _state.update { it.copy(alertActive = true, status = if (manual) "Sending MOB alert..." else "WATER DETECTED - sending...") }
        refreshNotification()

        alertJob = scope.launch {
            // Send straight away with whatever position we already have; a fresh
            // GPS fix can take a while and seconds matter.
            val first = lastLocation()
            val delivered = sender.postUntilDelivered(sender.payload("overboard", id, first)) { attempt, code ->
                _state.update { it.copy(status = "Alert attempt $attempt: ${code ?: "no signal"}") }
            }
            _state.update { it.copy(status = if (delivered) "ALERT SENT to WhatsApp" else "Alert rejected - check relay config") }
            refreshNotification()

            // Keep the boat updated with where the wearer actually is in the water.
            val until = System.currentTimeMillis() + POSITION_UPDATES_FOR_MS
            var seq = 1
            while (isActive && System.currentTimeMillis() < until) {
                val fix = freshLocation()
                if (fix != null) {
                    sender.postUntilDelivered(sender.payload("position", id, fix, seq++), giveUpAfterMs = POSITION_INTERVAL_MS)
                }
                delay(POSITION_INTERVAL_MS)
            }
        }
    }

    private fun imSafe() {
        val id = eventId
        alertJob?.cancel()
        alertJob = null
        eventId = null
        detector.reset()
        _state.update { it.copy(alertActive = false, status = "Sending 'I'm safe'...") }
        refreshNotification()
        if (id == null) {
            stopIfIdle()
            return
        }
        scope.launch {
            val ok = sender.postUntilDelivered(sender.payload("safe", id, null), giveUpAfterMs = 10 * 60_000L)
            _state.update { it.copy(status = if (ok) "Crew told you're safe" else "Could not send 'I'm safe'") }
            stopIfIdle()
        }
    }

    private fun sendTest() {
        _state.update { it.copy(status = "Sending test...") }
        scope.launch {
            val ok = sender.postUntilDelivered(
                sender.payload("test", UUID.randomUUID().toString(), lastLocation()),
                giveUpAfterMs = 30_000,
            ) { attempt, code -> _state.update { it.copy(status = "Test attempt $attempt: ${code ?: "no signal"}") } }
            _state.update { it.copy(status = if (ok) "Test sent - check WhatsApp" else "Test FAILED") }
            stopIfIdle()
        }
    }

    @SuppressLint("MissingPermission") // Checked in MainActivity before arming.
    private suspend fun lastLocation(): Location? =
        try { withTimeoutOrNull(2_000) { location.lastLocation.await() } } catch (e: Exception) { null }

    @SuppressLint("MissingPermission")
    private suspend fun freshLocation(): Location? = try {
        val cancel = CancellationTokenSource()
        withTimeoutOrNull(20_000) {
            location.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token).await()
        }.also { cancel.cancel() }
    } catch (e: Exception) {
        null
    }

    private fun vibrate() {
        val pattern = longArrayOf(0, 600, 200, 600, 200, 600)
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, if (s.alertActive) CHANNEL_ALERT else CHANNEL_ARMED)
            .setSmallIcon(R.drawable.ic_lifebuoy)
            .setContentIntent(open)
            .setOngoing(true)
            .setContentTitle(if (s.alertActive) "MAN OVERBOARD alert active" else "Overboard watch armed")
            .setContentText(s.status)
        if (s.alertActive) {
            val safe = PendingIntent.getService(
                this, 1, Intent(this, OverboardService::class.java).setAction(ACTION_SAFE), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "I'm safe", safe).setPriority(NotificationCompat.PRIORITY_MAX)
        }
        return builder.build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }
}
