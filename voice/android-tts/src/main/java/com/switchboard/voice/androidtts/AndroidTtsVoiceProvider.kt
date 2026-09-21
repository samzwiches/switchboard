package com.switchboard.voice.androidtts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.switchboard.voice.api.VoiceProvider
import com.switchboard.voice.api.VoiceProviderDescriptor
import com.switchboard.voice.api.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class AndroidTtsVoiceProvider(context: Context) : VoiceProvider {
    private val appContext = context.applicationContext
    private val initMutex = Mutex()
    private val mutableState = MutableStateFlow<VoiceState>(VoiceState.Uninitialized)
    private var engine: TextToSpeech? = null

    override val descriptor = VoiceProviderDescriptor(
        id = "android-tts",
        displayName = "Android Text to Speech",
    )
    override val state: StateFlow<VoiceState> = mutableState.asStateFlow()

    override suspend fun initialize(): Result<Unit> = initMutex.withLock {
        if (engine != null) return@withLock Result.success(Unit)

        runCatching {
            val status = CompletableDeferred<Int>()
            val newEngine = TextToSpeech(appContext) { result -> status.complete(result) }
            if (status.await() != TextToSpeech.SUCCESS) {
                newEngine.shutdown()
                error("Android TTS engine failed to initialize")
            }

            val languageResult = newEngine.setLanguage(Locale.getDefault())
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                newEngine.shutdown()
                error("The device TTS engine does not support the current language")
            }

            engine = newEngine
            mutableState.value = VoiceState.Ready
            Log.i(TAG, "Voice provider activated")
            Unit
        }.onFailure { error ->
            mutableState.value = VoiceState.Failed(error.message ?: "TTS initialization failed")
        }
    }

    override suspend fun speak(text: String): Result<Unit> {
        initialize().getOrElse { return Result.failure(it) }
        val activeEngine = engine ?: return Result.failure(IllegalStateException("TTS unavailable"))
        val utteranceId = UUID.randomUUID().toString()
        mutableState.value = VoiceState.Speaking(text)

        return suspendCancellableCoroutine { continuation ->
            activeEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == utteranceId) Log.i(TAG, "TTS STARTED")
                }

                override fun onDone(id: String?) {
                    if (id != utteranceId) return
                    mutableState.value = VoiceState.Ready
                    Log.i(TAG, "TTS FINISHED")
                    if (continuation.isActive) continuation.resume(Result.success(Unit))
                }

                @Deprecated("Deprecated by Android")
                override fun onError(id: String?) {
                    onError(id, TextToSpeech.ERROR)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id != utteranceId) return
                    val failure = IllegalStateException("Android TTS error $errorCode")
                    mutableState.value = VoiceState.Failed(failure.message.orEmpty())
                    Log.w(TAG, "TTS FAILED: Android error $errorCode")
                    if (continuation.isActive) continuation.resume(Result.failure(failure))
                }
            })

            val result = activeEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                val failure = IllegalStateException("Android TTS rejected the utterance")
                mutableState.value = VoiceState.Failed(failure.message.orEmpty())
                if (continuation.isActive) continuation.resume(Result.failure(failure))
            }
            continuation.invokeOnCancellation { activeEngine.stop() }
        }
    }

    override fun stop() {
        engine?.stop()
        if (engine != null) mutableState.value = VoiceState.Ready
    }

    override fun close() {
        engine?.shutdown()
        engine = null
        mutableState.value = VoiceState.Uninitialized
    }

    private companion object {
        const val TAG = "SwitchboardVoice"
    }
}
