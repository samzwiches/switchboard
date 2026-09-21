package com.switchboard.app

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.switchboard.app.service.WakeWordForegroundService
import com.switchboard.app.ui.SwitchboardApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwitchboardRoute()
        }
    }

    override fun onResume() {
        super.onResume()
        val graph = switchboardGraph
        val settings = graph.settings.settings.value
        if (settings.wakeWordEnabled && hasMicrophonePermission()) {
            runCatching { WakeWordForegroundService.start(this, settings.wakePhrase) }
                .onFailure { graph.settings.setWakeWordEnabled(false) }
        }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
private fun MainActivity.SwitchboardRoute() {
    val graph = switchboardGraph
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val assistantState by graph.assistantController.state.collectAsStateWithLifecycle()
    val wakeWordState by graph.wakeWordProvider.state.collectAsStateWithLifecycle()
    val speechInputState by graph.speechInputEngine.state.collectAsStateWithLifecycle()
    val foregroundServiceRunning by graph.wakeWordServiceMonitor.running.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var roleRefresh by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { results ->
        permissionRefresh++
        val microphoneGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (microphoneGranted) {
            graph.settings.setWakeWordEnabled(true)
            runCatching { WakeWordForegroundService.start(this, settings.wakePhrase) }
                .onFailure { error ->
                    graph.settings.setWakeWordEnabled(false)
                    permissionMessage = error.message ?: "Android could not start wake word listening."
                }
        } else {
            graph.settings.setWakeWordEnabled(false)
            permissionMessage = "Microphone permission is required for wake word listening."
        }
    }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        roleRefresh++
    }

    // Read the counters so state refreshes after platform result callbacks.
    permissionRefresh
    roleRefresh
    val microphoneGranted = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val roleStatus = assistantRoleStatus()

    SwitchboardApp(
        settings = settings,
        assistantState = assistantState,
        wakeWordState = wakeWordState,
        speechInputState = speechInputState,
        openAiConfigured = graph.openAiProvider.config.isConfigured,
        foregroundServiceRunning = foregroundServiceRunning,
        microphoneGranted = microphoneGranted,
        roleStatus = roleStatus,
        permissionMessage = permissionMessage,
        onWakePhraseChanged = graph.settings::setWakePhrase,
        onWakeWordToggled = { enabled ->
            permissionMessage = null
            if (!enabled) {
                graph.settings.setWakeWordEnabled(false)
                WakeWordForegroundService.stop(this)
            } else if (microphoneGranted) {
                graph.settings.setWakeWordEnabled(true)
                runCatching { WakeWordForegroundService.start(this, settings.wakePhrase) }
                    .onFailure { error ->
                        graph.settings.setWakeWordEnabled(false)
                        permissionMessage = error.message ?: "Android could not start wake word listening."
                    }
            } else {
                val permissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                permissionLauncher.launch(permissions.toTypedArray())
            }
        },
        onBackendUrlSaved = { url ->
            graph.settings.setBackendUrl(url)
            scope.launch { graph.assistantController.onProviderChanged() }
        },
        onProviderSelected = { providerId ->
            graph.settings.setSelectedProvider(providerId)
            scope.launch { graph.assistantController.onProviderChanged() }
        },
        onRequestAssistantRole = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                    !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
                ) {
                    roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                }
            }
        },
        onSimulateWakeWord = {
            permissionMessage = null
            scope.launch {
                runCatching {
                    graph.assistantController.startListening(settings.wakePhrase)
                    graph.assistantController.simulateWakeWord()
                }.onFailure { error ->
                    permissionMessage = error.message ?: "The developer wake event could not be triggered."
                }
            }
        },
        onSendMessage = graph.assistantController::sendText,
        onDismissConversation = {
            scope.launch { graph.assistantController.dismissConversation() }
        },
    )
}

private fun MainActivity.assistantRoleStatus(): AssistantRoleStatus {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return AssistantRoleStatus.Unsupported
    val roleManager = getSystemService(RoleManager::class.java)
    if (!roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
        return AssistantRoleStatus.Unsupported
    }
    return if (roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
        AssistantRoleStatus.Held
    } else {
        AssistantRoleStatus.Available
    }
}

enum class AssistantRoleStatus { Available, Held, Unsupported }
