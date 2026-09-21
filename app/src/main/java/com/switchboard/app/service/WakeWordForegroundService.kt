package com.switchboard.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.switchboard.app.MainActivity
import com.switchboard.app.R
import com.switchboard.app.switchboardGraph
import com.switchboard.core.assistant.AssistantPhase
import com.switchboard.core.wakeword.DEFAULT_WAKE_PHRASE
import com.switchboard.core.wakeword.WakeWordPhase
import com.switchboard.core.wakeword.WakeWordState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class WakeWordForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationUpdates: Job? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            switchboardGraph.settings.setWakeWordEnabled(false)
            stopListeningAndSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "MICROPHONE ERROR: permission is missing")
            switchboardGraph.settings.setWakeWordEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val phrase = intent?.getStringExtra(EXTRA_WAKE_PHRASE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_WAKE_PHRASE

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(
                WakeWordState(
                    phase = WakeWordPhase.Arming,
                    phrase = phrase,
                    detectorName = switchboardGraph.wakeWordProvider.state.value.detectorName,
                ),
                AssistantPhase.Idle,
            ),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
        isForeground = true
        switchboardGraph.wakeWordServiceMonitor.setRunning(true)
        collectNotificationState()
        Log.i(TAG, "Wake word service started")
        serviceScope.launch {
            switchboardGraph.assistantController.startListening(phrase)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isForeground = false
        switchboardGraph.wakeWordServiceMonitor.setRunning(false)
        notificationUpdates?.cancel()
        serviceScope.launch {
            switchboardGraph.assistantController.stopListening()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopListeningAndSelf() {
        serviceScope.launch {
            switchboardGraph.assistantController.stopListening()
            isForeground = false
            switchboardGraph.wakeWordServiceMonitor.setRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun collectNotificationState() {
        if (notificationUpdates?.isActive == true) return
        notificationUpdates = serviceScope.launch {
            combine(
                switchboardGraph.wakeWordProvider.state,
                switchboardGraph.assistantController.state,
            ) { wakeState, assistantState -> wakeState to assistantState.phase }
                .collect { (state, assistantPhase) ->
                if (isForeground) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(state, assistantPhase))
                }
            }
        }
    }

    private fun buildNotification(
        state: WakeWordState,
        assistantPhase: AssistantPhase,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val assistantText = when (assistantPhase) {
            AssistantPhase.Listening -> "Lucas is listening" to "Speak now"
            AssistantPhase.Thinking -> "Lucas is thinking" to "Contacting the AI service"
            AssistantPhase.Speaking -> "Lucas is responding" to "Wake detector paused"
            AssistantPhase.Error -> "Switchboard needs attention" to "Open the app for details"
            AssistantPhase.Idle -> null
        }
        val (title, detail) = assistantText ?: when (state.phase) {
            WakeWordPhase.Stopped -> "Switchboard is stopping" to "Microphone inactive"
            WakeWordPhase.Arming -> "Switchboard is starting" to "Preparing the local wake detector"
            WakeWordPhase.ListeningForWakeWord -> getString(R.string.wake_word_notification_title) to
                getString(R.string.wake_word_notification_text, state.phrase)
            WakeWordPhase.WakeDetected -> "Switchboard heard you" to "Starting Lucas"
            WakeWordPhase.AssistantActive -> "Lucas is responding" to "Wake detector paused"
            WakeWordPhase.Rearming -> "Switchboard is rearming" to "Preparing the microphone"
            WakeWordPhase.Error -> "Switchboard needs attention" to
                (state.errorMessage ?: "Wake detector error")
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.stop_listening), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.wake_word_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.wake_word_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SwitchboardWakeSvc"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "wake_word_listening"
        private const val ACTION_START = "com.switchboard.app.action.START_WAKE_WORD"
        private const val ACTION_STOP = "com.switchboard.app.action.STOP_WAKE_WORD"
        private const val EXTRA_WAKE_PHRASE = "wake_phrase"

        fun start(context: Context, phrase: String) {
            val intent = Intent(context, WakeWordForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_WAKE_PHRASE, phrase)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeWordForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
