package com.switchboard.voice.androidspeech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.switchboard.voice.speech.api.SpeechInputConfig
import com.switchboard.voice.speech.api.SpeechInputDescriptor
import com.switchboard.voice.speech.api.SpeechInputEngine
import com.switchboard.voice.speech.api.SpeechInputResult
import com.switchboard.voice.speech.api.SpeechInputState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** One-shot post-wake dictation. Continuous keyword spotting remains in sherpa-onnx. */
class AndroidSpeechInputEngine(context: Context) : SpeechInputEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenMutex = Mutex()
    private val mutableState = MutableStateFlow<SpeechInputState>(SpeechInputState.Idle)
    private var activeRecognizer: SpeechRecognizer? = null
    private var activeContinuation: CancellableContinuation<SpeechInputResult>? = null

    private val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
    private val recognitionAvailable = onDeviceAvailable || SpeechRecognizer.isRecognitionAvailable(appContext)

    override val descriptor = SpeechInputDescriptor(
        id = "android-speech-recognizer",
        displayName = if (onDeviceAvailable) {
            "Android SpeechRecognizer (on-device)"
        } else {
            "Android SpeechRecognizer"
        },
        available = recognitionAvailable,
    )
    override val state: StateFlow<SpeechInputState> = mutableState.asStateFlow()

    override suspend fun listen(config: SpeechInputConfig): SpeechInputResult = listenMutex.withLock {
        if (!recognitionAvailable) {
            mutableState.value = SpeechInputState.Unavailable
            return@withLock SpeechInputResult.Failed("Speech recognition is unavailable on this device.")
        }

        val result = withTimeoutOrNull(config.timeoutMillis.coerceAtLeast(1_000L)) {
            withContext(Dispatchers.Main.immediate) { listenOnce(config) }
        }
        if (result == null) {
            stop()
            Log.i(TAG, "SPEECH LISTENING TIMED OUT")
            SpeechInputResult.NoSpeech("No speech was detected.")
        } else {
            result
        }
    }

    override fun stop() {
        runOnMain {
            activeRecognizer?.cancel()
            activeRecognizer?.destroy()
            activeRecognizer = null
            activeContinuation?.let { continuation ->
                if (continuation.isActive) continuation.resume(SpeechInputResult.Cancelled)
            }
            activeContinuation = null
            if (recognitionAvailable) mutableState.value = SpeechInputState.Idle
        }
    }

    override fun close() = stop()

    private suspend fun listenOnce(config: SpeechInputConfig): SpeechInputResult =
        suspendCancellableCoroutine { continuation ->
            val recognizer = runCatching { createRecognizer() }
                .getOrElse { error ->
                    val reason = error.message ?: "Speech recognition could not start."
                    mutableState.value = SpeechInputState.Failed(reason)
                    continuation.resume(SpeechInputResult.Failed(reason))
                    return@suspendCancellableCoroutine
                }
            activeRecognizer = recognizer
            activeContinuation = continuation

            fun finish(result: SpeechInputResult) {
                if (activeRecognizer !== recognizer) return
                recognizer.cancel()
                recognizer.destroy()
                activeRecognizer = null
                activeContinuation = null
                mutableState.value = when (result) {
                    is SpeechInputResult.Failed -> SpeechInputState.Failed(result.reason)
                    else -> SpeechInputState.Idle
                }
                if (continuation.isActive) continuation.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit

                override fun onBeginningOfSpeech() {
                    mutableState.value = SpeechInputState.SpeechDetected
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    finish(mapError(error))
                }

                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()
                    if (transcript == null) {
                        finish(SpeechInputResult.NoSpeech("No speech was detected."))
                    } else {
                        Log.i(TAG, "SPEECH RESULT RECEIVED: ${transcript.length} characters")
                        finish(SpeechInputResult.Transcript(transcript))
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.localeTag ?: Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, config.preferOffline)
            }
            continuation.invokeOnCancellation {
                mainHandler.post {
                    if (activeRecognizer === recognizer) {
                        recognizer.cancel()
                        recognizer.destroy()
                        activeRecognizer = null
                        activeContinuation = null
                        mutableState.value = SpeechInputState.Idle
                    }
                }
            }

            runCatching {
                mutableState.value = SpeechInputState.Listening
                Log.i(TAG, "SPEECH LISTENING STARTED")
                recognizer.startListening(intent)
            }.onFailure { error ->
                finish(SpeechInputResult.Failed(error.message ?: "Speech recognition could not start."))
            }
        }

    private fun createRecognizer(): SpeechRecognizer =
        if (onDeviceAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }

    private fun mapError(error: Int): SpeechInputResult = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> SpeechInputResult.NoSpeech("No speech was detected.")

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechInputResult.Failed("The microphone is busy.")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SpeechInputResult.Failed("Microphone permission is required.")
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> SpeechInputResult.Failed("Speech recognition could not reach its service.")

        SpeechRecognizer.ERROR_AUDIO -> SpeechInputResult.Failed("Speech recognition could not use the microphone.")
        else -> SpeechInputResult.Failed("Speech recognition failed (Android code $error).")
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "SwitchboardSpeech"
    }
}
