package com.switchboard.wakeword.sherpa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.switchboard.core.wakeword.DEFAULT_WAKE_PHRASE
import com.switchboard.core.wakeword.WakeWordConfig
import com.switchboard.core.wakeword.WakeWordDetector
import com.switchboard.core.wakeword.WakeWordDetectorEvent
import com.switchboard.core.wakeword.WakeWordDetectorException
import com.switchboard.core.wakeword.WakeWordErrorKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Fully local, open-vocabulary keyword spotting backed by sherpa-onnx. */
class SherpaOnnxWakeWordDetector(
    context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WakeWordDetector {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val mutableEvents = MutableSharedFlow<WakeWordDetectorEvent>(extraBufferCapacity = 16)
    private var keywordSpotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    override val name: String = "sherpa-onnx · local"
    override val events: Flow<WakeWordDetectorEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(config: WakeWordConfig) {
        val phrase = config.phrase.trim().ifBlank { DEFAULT_WAKE_PHRASE }
        if (!phrase.equals(DEFAULT_WAKE_PHRASE, ignoreCase = true)) {
            throw WakeWordDetectorException(
                message = "The bundled V0.2 keyword file supports only “$DEFAULT_WAKE_PHRASE”.",
                kind = WakeWordErrorKind.Detector,
                recoverable = false,
            )
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw WakeWordDetectorException(
                message = "Microphone permission is not granted.",
                kind = WakeWordErrorKind.Microphone,
                recoverable = false,
            )
        }

        val alreadyRunning = mutex.withLock { captureJob?.isActive == true }
        if (alreadyRunning) return
        // Clean up a completed/erroring capture before a manual or automatic restart.
        stop()

        val spotter = ensureKeywordSpotter()
        val newStream = withContext(ioDispatcher) {
            runCatching { spotter.createStream() }
                .getOrElse { failure ->
                    throw WakeWordDetectorException(
                        message = "The local Hey Lucas keyword stream could not be created.",
                        kind = WakeWordErrorKind.Detector,
                        recoverable = false,
                        cause = failure,
                    )
                }
        }
        if (newStream.ptr == 0L) {
            newStream.release()
            throw WakeWordDetectorException(
                message = "The local Hey Lucas keyword file is invalid.",
                kind = WakeWordErrorKind.Detector,
                recoverable = false,
            )
        }

        val recorder = runCatching { createAudioRecord() }
            .getOrElse { failure ->
                newStream.release()
                throw WakeWordDetectorException(
                    message = failure.message ?: "The microphone is unavailable.",
                    kind = WakeWordErrorKind.Microphone,
                    recoverable = true,
                    cause = failure,
                )
            }

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error("Android did not start microphone recording.")
            }
        } catch (failure: Throwable) {
            recorder.release()
            newStream.release()
            throw WakeWordDetectorException(
                message = "The microphone could not start. It may be in use by another app.",
                kind = WakeWordErrorKind.Microphone,
                recoverable = true,
                cause = failure,
            )
        }

        mutex.withLock {
            if (captureJob?.isActive == true) {
                recorder.stopAndRelease()
                newStream.release()
                return
            }
            audioRecord = recorder
            stream = newStream
            captureJob = scope.launch(ioDispatcher) {
                captureAudio(recorder, spotter, newStream, phrase)
            }
        }
        mutableEvents.emit(WakeWordDetectorEvent.MicrophoneActive)
    }

    override suspend fun stop() {
        val resources = mutex.withLock {
            val current = CaptureResources(captureJob, audioRecord, stream)
            captureJob = null
            audioRecord = null
            stream = null
            current
        }
        resources.job?.cancel()
        withContext(ioDispatcher) {
            resources.recorder?.stopSafely()
        }
        resources.job?.cancelAndJoin()
        withContext(ioDispatcher) {
            resources.recorder?.release()
            resources.stream?.release()
        }
        if (resources.job != null || resources.recorder != null || resources.stream != null) {
            mutableEvents.emit(WakeWordDetectorEvent.MicrophoneStopped)
        }
    }

    override suspend fun release() {
        stop()
        val spotter = mutex.withLock {
            keywordSpotter.also { keywordSpotter = null }
        }
        withContext(ioDispatcher) {
            spotter?.release()
        }
    }

    private suspend fun ensureKeywordSpotter(): KeywordSpotter {
        mutex.withLock { keywordSpotter?.let { return it } }
        verifyModelAssets()
        val created = withContext(ioDispatcher) {
            runCatching {
                KeywordSpotter(
                    assetManager = appContext.assets,
                    config = KeywordSpotterConfig(
                        featConfig = FeatureConfig(
                            sampleRate = SAMPLE_RATE_HZ,
                            featureDim = FEATURE_DIMENSION,
                        ),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = "$MODEL_DIRECTORY/$ENCODER_FILE",
                                decoder = "$MODEL_DIRECTORY/$DECODER_FILE",
                                joiner = "$MODEL_DIRECTORY/$JOINER_FILE",
                            ),
                            tokens = "$MODEL_DIRECTORY/$TOKENS_FILE",
                            numThreads = 2,
                            debug = false,
                            provider = "cpu",
                            modelType = "zipformer2",
                        ),
                        maxActivePaths = 4,
                        keywordsFile = "$MODEL_DIRECTORY/$KEYWORDS_FILE",
                        keywordsScore = 1.5f,
                        keywordsThreshold = 0.25f,
                        numTrailingBlanks = 1,
                    ),
                )
            }.getOrElse { failure ->
                throw WakeWordDetectorException(
                    message = "The bundled local wake detector could not initialize.",
                    kind = WakeWordErrorKind.Detector,
                    recoverable = false,
                    cause = failure,
                )
            }
        }
        return mutex.withLock {
            keywordSpotter ?: created.also { keywordSpotter = it }
        }
    }

    private fun verifyModelAssets() {
        val packaged = runCatching { appContext.assets.list(MODEL_DIRECTORY)?.toSet().orEmpty() }
            .getOrElse { emptySet() }
        val missing = REQUIRED_MODEL_FILES - packaged
        if (missing.isNotEmpty()) {
            throw WakeWordDetectorException(
                message = "Local wake model is missing: ${missing.sorted().joinToString()}.",
                kind = WakeWordErrorKind.Detector,
                recoverable = false,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minimumBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBytes <= 0) error("Android reported no usable microphone buffer.")

        val bufferBytes = max(minimumBytes * 2, FRAMES_PER_READ * Short.SIZE_BYTES * 2)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("Android could not initialize the microphone.")
        }
        return recorder
    }

    private suspend fun captureAudio(
        recorder: AudioRecord,
        spotter: KeywordSpotter,
        activeStream: OnlineStream,
        phrase: String,
    ) {
        val pcm = ShortArray(FRAMES_PER_READ)
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    throw IllegalStateException("Microphone read failed with Android code $count.")
                }
                mutableEvents.tryEmit(WakeWordDetectorEvent.AudioLevel(PcmLevel.normalized(pcm, count)))
                val samples = FloatArray(count) { index -> pcm[index] / 32768f }
                activeStream.acceptWaveform(samples, SAMPLE_RATE_HZ)

                while (spotter.isReady(activeStream)) {
                    spotter.decode(activeStream)
                    if (spotter.getResult(activeStream).keyword.isNotBlank()) {
                        spotter.reset(activeStream)
                        recorder.stopSafely()
                        mutableEvents.emit(WakeWordDetectorEvent.MicrophoneStopped)
                        mutableEvents.emit(WakeWordDetectorEvent.Detected(phrase))
                        return
                    }
                }
            }
        } catch (failure: Throwable) {
            if (kotlin.coroutines.coroutineContext.isActive) {
                mutableEvents.emit(
                    WakeWordDetectorEvent.Error(
                        message = failure.message ?: "Microphone capture failed.",
                        kind = WakeWordErrorKind.Microphone,
                        recoverable = true,
                    ),
                )
            }
        }
    }

    private fun AudioRecord.stopSafely() {
        if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { stop() }
        }
    }

    private fun AudioRecord.stopAndRelease() {
        stopSafely()
        release()
    }

    private data class CaptureResources(
        val job: Job?,
        val recorder: AudioRecord?,
        val stream: OnlineStream?,
    )

    companion object {
        const val MODEL_DIRECTORY = "sherpa-onnx-kws-gigaspeech-hey-lucas"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val FEATURE_DIMENSION = 80
        private const val FRAMES_PER_READ = 1_600
        private const val ENCODER_FILE = "encoder.int8.onnx"
        private const val DECODER_FILE = "decoder.onnx"
        private const val JOINER_FILE = "joiner.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val KEYWORDS_FILE = "keywords.txt"
        private val REQUIRED_MODEL_FILES = setOf(
            ENCODER_FILE,
            DECODER_FILE,
            JOINER_FILE,
            TOKENS_FILE,
            KEYWORDS_FILE,
        )
    }
}
