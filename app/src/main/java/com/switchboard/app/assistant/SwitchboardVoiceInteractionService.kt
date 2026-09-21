package com.switchboard.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

class SwitchboardVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "Switchboard is the active VoiceInteractionService")
    }

    override fun onPrepareToShowSession(args: Bundle, flags: Int) {
        super.onPrepareToShowSession(args, flags)
        Log.d(TAG, "Preparing assistant session")
    }

    private companion object {
        const val TAG = "SwitchboardRole"
    }
}

