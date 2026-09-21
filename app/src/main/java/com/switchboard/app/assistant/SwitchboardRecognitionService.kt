package com.switchboard.app.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Registration-only recognizer for the assistant role contract.
 * V0.2 wake detection is separate from assistant dictation. This role-facing service remains a
 * registration placeholder until a real post-wake speech-to-text provider is added.
 */
class SwitchboardRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) {
        listener.results(Bundle.EMPTY)
    }
}
