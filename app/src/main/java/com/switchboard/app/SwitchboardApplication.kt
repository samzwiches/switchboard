package com.switchboard.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.switchboard.app.settings.resolveBackendUrl
import com.switchboard.app.settings.isAndroidEmulator
import com.switchboard.core.actions.DefaultActionRouter
import com.switchboard.core.actions.MockActionHandler
import com.switchboard.core.assistant.AssistantController
import com.switchboard.core.wakeword.DefaultWakeWordProvider
import com.switchboard.providers.openai.MockOpenAiProvider
import com.switchboard.providers.openai.OpenAiBackendConfig
import com.switchboard.providers.openai.OpenAiBackendProvider
import com.switchboard.voice.androidspeech.AndroidSpeechInputEngine
import com.switchboard.voice.androidtts.AndroidTtsVoiceProvider
import com.switchboard.app.settings.SwitchboardSettingsRepository
import com.switchboard.app.service.WakeWordServiceMonitor
import com.switchboard.wakeword.sherpa.SherpaOnnxWakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SwitchboardApplication : Application() {
    lateinit var graph: SwitchboardGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = SwitchboardGraph(this)
    }
}

class SwitchboardGraph(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SwitchboardSettingsRepository(context, resolveBackendUrl(
        savedUrl = null,
        configuredUrl = BuildConfig.SWITCHBOARD_BACKEND_URL,
        debug = BuildConfig.DEBUG,
        emulator = isAndroidEmulator(Build.FINGERPRINT, Build.MODEL, Build.HARDWARE, Build.PRODUCT),
        emulatorUrl = BuildConfig.DEBUG_EMULATOR_BACKEND_URL,
        lanUrl = BuildConfig.DEBUG_LAN_BACKEND_URL,
    ))
    val wakeWordServiceMonitor = WakeWordServiceMonitor()
    val wakeWordDetector = SherpaOnnxWakeWordDetector(context, applicationScope)
    val wakeWordProvider = DefaultWakeWordProvider(wakeWordDetector, applicationScope)
    val mockAiProvider = MockOpenAiProvider()
    private var cachedOpenAiProvider: OpenAiBackendProvider? = null
    val openAiProvider: OpenAiBackendProvider
        get() {
            val url = settings.settings.value.backendUrl
            return cachedOpenAiProvider?.takeIf { it.config.baseUrl == url }
                ?: OpenAiBackendProvider(OpenAiBackendConfig(
                    baseUrl = url,
                    configurationErrorMessage = if (BuildConfig.DEBUG) {
                        "Switchboard backend isn’t configured. Open Debug Settings to set the backend address."
                    } else "OpenAI backend URL is not configured.",
                )).also {
                    cachedOpenAiProvider = it
                }
        }
    val voiceProvider = AndroidTtsVoiceProvider(context)
    val speechInputEngine = AndroidSpeechInputEngine(context)
    val actionRouter = DefaultActionRouter(listOf(MockActionHandler()))
    val assistantController = AssistantController(
        providerResolver = {
            if (settings.settings.value.selectedProviderId == openAiProvider.descriptor.id) {
                openAiProvider
            } else mockAiProvider
        },
        wakeWordProvider = wakeWordProvider,
        actionRouter = actionRouter,
        voiceProvider = voiceProvider,
        speechInputEngine = speechInputEngine,
        scope = applicationScope,
    )
}

val Context.switchboardGraph: SwitchboardGraph
    get() = (applicationContext as SwitchboardApplication).graph
