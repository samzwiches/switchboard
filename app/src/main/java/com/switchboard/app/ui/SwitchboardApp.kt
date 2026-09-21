package com.switchboard.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.switchboard.app.BuildConfig
import com.switchboard.app.AssistantRoleStatus
import com.switchboard.app.settings.SwitchboardSettings
import com.switchboard.providers.openai.MOCK_OPENAI_PROVIDER_ID
import com.switchboard.providers.openai.OPENAI_BACKEND_PROVIDER_ID
import com.switchboard.core.assistant.AssistantMessage
import com.switchboard.core.assistant.AssistantPhase
import com.switchboard.core.assistant.AssistantUiState
import com.switchboard.core.assistant.MessageAuthor
import com.switchboard.core.wakeword.WakeWordPhase
import com.switchboard.core.wakeword.WakeWordState
import com.switchboard.voice.speech.api.SpeechInputState
import com.switchboard.providers.openai.BackendHealthChecker
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF17211C)
private val Paper = Color(0xFFF4F2EB)
private val Moss = Color(0xFF315C49)
private val SoftMoss = Color(0xFFDCE7DF)
private val Rust = Color(0xFFA54A2A)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwitchboardApp(
    settings: SwitchboardSettings,
    assistantState: AssistantUiState,
    wakeWordState: WakeWordState,
    speechInputState: SpeechInputState,
    openAiConfigured: Boolean,
    foregroundServiceRunning: Boolean,
    microphoneGranted: Boolean,
    roleStatus: AssistantRoleStatus,
    permissionMessage: String?,
    onWakePhraseChanged: (String) -> Unit,
    onWakeWordToggled: (Boolean) -> Unit,
    onProviderSelected: (String) -> Unit,
    onBackendUrlSaved: (String) -> Unit,
    onBackendUrlReset: () -> Unit,
    onRequestAssistantRole: () -> Unit,
    onSimulateWakeWord: () -> Unit,
    onSendMessage: (String) -> Unit,
    onDismissConversation: () -> Unit,
) {
    val debugSettings = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    MaterialTheme {
        Surface(color = Paper, contentColor = Ink, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header()
                run {
                    AssistantConversation(
                        state = assistantState,
                        onSendMessage = onSendMessage,
                        onDismiss = onDismissConversation,
                    )
                }
                if (BuildConfig.DEBUG && !openAiConfigured) {
                    Text("Switchboard backend isn’t configured. Open Debug Settings to set the backend address.")
                    OutlinedButton(onClick = { scope.launch { debugSettings.bringIntoView() } }) {
                        Text("Open Debug Settings")
                    }
                }
                WakeDiagnosticsCard(
                    wakeWordState = wakeWordState,
                    assistantState = assistantState,
                    speechInputState = speechInputState,
                    settings = settings,
                    openAiConfigured = openAiConfigured,
                    foregroundServiceRunning = foregroundServiceRunning,
                )
                SettingsCard(
                    modifier = Modifier.bringIntoViewRequester(debugSettings),
                    settings = settings,
                    microphoneGranted = microphoneGranted,
                    roleStatus = roleStatus,
                    permissionMessage = permissionMessage,
                    openAiConfigured = openAiConfigured,
                    onWakePhraseChanged = onWakePhraseChanged,
                    onWakeWordToggled = onWakeWordToggled,
                    onProviderSelected = onProviderSelected,
                    onBackendUrlSaved = onBackendUrlSaved,
                    onBackendUrlReset = onBackendUrlReset,
                    onRequestAssistantRole = onRequestAssistantRole,
                    onSimulateWakeWord = onSimulateWakeWord,
                )
                Text(
                    text = "V0.2 · Local wake word · Backend-held OpenAI key",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "SWITCHBOARD",
            style = MaterialTheme.typography.labelMedium,
            color = Moss,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Your assistant. Your choices.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "A replaceable shell for models, wake words, voices, and actions.",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    settings: SwitchboardSettings,
    microphoneGranted: Boolean,
    roleStatus: AssistantRoleStatus,
    permissionMessage: String?,
    openAiConfigured: Boolean,
    onWakePhraseChanged: (String) -> Unit,
    onWakeWordToggled: (Boolean) -> Unit,
    onProviderSelected: (String) -> Unit,
    onBackendUrlSaved: (String) -> Unit,
    onBackendUrlReset: () -> Unit,
    onRequestAssistantRole: () -> Unit,
    onSimulateWakeWord: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Assistant settings", style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selected AI provider", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.selectedProviderId == MOCK_OPENAI_PROVIDER_ID,
                        onClick = { onProviderSelected(MOCK_OPENAI_PROVIDER_ID) },
                        label = { Text("OpenAI (mock)") },
                    )
                    FilterChip(
                        selected = settings.selectedProviderId == OPENAI_BACKEND_PROVIDER_ID,
                        onClick = { onProviderSelected(OPENAI_BACKEND_PROVIDER_ID) },
                        label = { Text("OpenAI") },
                    )
                }
                Text(
                    text = when {
                        settings.selectedProviderId == MOCK_OPENAI_PROVIDER_ID ->
                            "Offline mock — no network request is made"
                        openAiConfigured -> "Backend address configured"
                        else -> if (BuildConfig.DEBUG) "Open Debug Settings below to configure the backend." else "Unavailable — backend URL is not configured"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.selectedProviderId == OPENAI_BACKEND_PROVIDER_ID &&
                        !openAiConfigured
                    ) Rust else Ink.copy(alpha = 0.65f),
                )
            }
            if (BuildConfig.DEBUG) {
                Text("Debug Settings", style = MaterialTheme.typography.titleMedium)
                var backendUrl by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
                var healthResult by remember(backendUrl) { mutableStateOf<String?>(null) }
                var testing by remember { mutableStateOf(false) }
                val healthChecker = remember { BackendHealthChecker() }
                val healthScope = rememberCoroutineScope()
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    enabled = !testing,
                    label = { Text("Backend URL") },
                    supportingText = { Text("Server address only. Never enter an API key.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(enabled = !testing, onClick = { onBackendUrlSaved(backendUrl) }) {
                    Text("Save backend URL")
                }
                OutlinedButton(enabled = !testing, onClick = onBackendUrlReset) {
                    Text("Use development default")
                }
                OutlinedButton(enabled = !testing, onClick = {
                    val address = backendUrl
                    testing = true
                    healthScope.launch {
                        try { healthResult = healthChecker.check(address) }
                        finally { testing = false }
                    }
                }) {
                    Text(if (testing) "TESTING…" else "TEST BACKEND")
                }
                if (backendUrl != settings.backendUrl) Text("Save this address to use it for conversations.")
                healthResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            OutlinedTextField(
                value = settings.wakePhrase,
                onValueChange = onWakePhraseChanged,
                label = { Text("Wake phrase") },
                supportingText = { Text("Bundled local keyword model") },
                singleLine = true,
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable Wake Word", fontWeight = FontWeight.Medium)
                    Text(
                        if (microphoneGranted) "Microphone permission granted" else "Permission requested when enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.copy(alpha = 0.65f),
                    )
                }
                Switch(
                    checked = settings.wakeWordEnabled,
                    onCheckedChange = onWakeWordToggled,
                )
            }
            permissionMessage?.let {
                Text(it, color = Rust, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider(color = Ink.copy(alpha = 0.12f))
            Button(
                onClick = onRequestAssistantRole,
                enabled = roleStatus == AssistantRoleStatus.Available,
                colors = ButtonDefaults.buttonColors(containerColor = Moss),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Set Switchboard as Default Assistant")
            }
            Text(
                text = when (roleStatus) {
                    AssistantRoleStatus.Held -> "Switchboard currently holds the Android assistant role."
                    AssistantRoleStatus.Available -> "Android will show a system-controlled approval screen."
                    AssistantRoleStatus.Unsupported -> "This device does not expose the Android assistant role."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ink.copy(alpha = 0.68f),
            )
            OutlinedButton(
                onClick = onSimulateWakeWord,
                enabled = settings.wakeWordEnabled && microphoneGranted && settings.wakePhrase.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Developer · Simulate “${settings.wakePhrase.ifBlank { "Hey Lucas" }}”")
            }
        }
    }
}

@Composable
private fun WakeDiagnosticsCard(
    wakeWordState: WakeWordState,
    assistantState: AssistantUiState,
    speechInputState: SpeechInputState,
    settings: SwitchboardSettings,
    openAiConfigured: Boolean,
    foregroundServiceRunning: Boolean,
) {
    var expanded by remember { mutableStateOf(true) }
    val detectorStatus = when (wakeWordState.phase) {
        WakeWordPhase.Stopped -> "Stopped"
        WakeWordPhase.Arming -> "Arming"
        WakeWordPhase.ListeningForWakeWord -> "Armed"
        WakeWordPhase.WakeDetected -> "Wake detected"
        WakeWordPhase.AssistantActive -> "Paused for assistant"
        WakeWordPhase.Rearming -> "Rearming"
        WakeWordPhase.Error -> "Error"
    }
    val assistantStatus = when (assistantState.phase) {
        AssistantPhase.Idle -> "Idle"
        AssistantPhase.Listening -> "Listening"
        AssistantPhase.Thinking -> "Thinking"
        AssistantPhase.Speaking -> "Speaking"
        AssistantPhase.Error -> "Error"
    }
    val wakeTime = wakeWordState.lastWakeAtMillis?.let { millis ->
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))
    } ?: "Never"
    val speechTime = assistantState.lastSpeechAtMillis?.let { millis ->
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))
    } ?: "Never"
    val providerTime = assistantState.lastProviderResultAtMillis?.let { millis ->
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))
    } ?: "Never"
    val speechStatus = when (speechInputState) {
        SpeechInputState.Idle -> "Idle"
        SpeechInputState.Listening -> "Listening"
        SpeechInputState.SpeechDetected -> "Speech detected"
        is SpeechInputState.Failed -> "Error"
        SpeechInputState.Unavailable -> "Unavailable"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SoftMoss),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wake word diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (wakeWordState.microphoneActive) {
                            "Live microphone activity"
                        } else {
                            "Microphone is not capturing"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.copy(alpha = 0.68f),
                    )
                }
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            LinearProgressIndicator(
                progress = { wakeWordState.audioLevel.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = Moss,
                trackColor = Color.White.copy(alpha = 0.75f),
            )
            if (expanded) {
                DiagnosticRow(
                    "Foreground service",
                    if (foregroundServiceRunning) "Running" else "Stopped",
                )
                DiagnosticRow(
                    "Microphone",
                    if (wakeWordState.microphoneActive) "Active" else "Inactive",
                )
                DiagnosticRow("Wake detector", detectorStatus)
                DiagnosticRow("Engine", wakeWordState.detectorName)
                DiagnosticRow("Speech recognizer", speechStatus)
                DiagnosticRow("Selected provider", settings.selectedProviderName)
                DiagnosticRow(
                    "Provider configuration",
                    if (settings.selectedProviderId == MOCK_OPENAI_PROVIDER_ID) {
                        "Offline"
                    } else if (openAiConfigured) {
                        "Configured"
                    } else {
                        "Missing backend URL"
                    },
                )
                DiagnosticRow(
                    "Last audio level",
                    "%.2f".format(Locale.US, wakeWordState.audioLevel),
                )
                DiagnosticRow("Last wake event", wakeTime)
                DiagnosticRow("Last speech event", speechTime)
                DiagnosticRow("Last provider result", providerTime)
                DiagnosticRow("Assistant session", assistantStatus)
                wakeWordState.errorMessage?.let { message ->
                    Text(message, color = Rust, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.68f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AssistantConversation(
    state: AssistantUiState,
    onSendMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Card(
        colors = CardDefaults.cardColors(containerColor = SoftMoss),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("LUCAS", style = MaterialTheme.typography.labelMedium, color = Moss)
                    Text(state.status, style = MaterialTheme.typography.titleLarge)
                }
                OutlinedButton(onClick = onDismiss) { Text("Clear") }
            }
            state.messages.forEach { MessageBubble(it) }
            state.error?.let { Text(it, color = Rust, style = MaterialTheme.typography.bodySmall) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Message Lucas") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        onSendMessage(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && state.phase !in setOf(
                        AssistantPhase.Listening,
                        AssistantPhase.Thinking,
                        AssistantPhase.Speaking,
                    ),
                    colors = ButtonDefaults.buttonColors(containerColor = Moss),
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AssistantMessage) {
    val isUser = message.author == MessageAuthor.User
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isUser) Color.White.copy(alpha = 0.7f) else Moss,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (isUser) "You" else "Lucas",
                style = MaterialTheme.typography.labelSmall,
                color = if (isUser) Moss else Color.White.copy(alpha = 0.75f),
            )
            Text(
                text = message.text,
                color = if (isUser) Ink else Color.White,
            )
        }
    }
}
