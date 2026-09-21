package com.switchboard.app.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import com.switchboard.app.MainActivity

class SwitchboardVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        SwitchboardVoiceInteractionSession(this)
}

private class SwitchboardVoiceInteractionSession(
    private val service: VoiceInteractionSessionService,
) : VoiceInteractionSession(service) {
    override fun onCreateContentView(): View = ComposeView(service).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            SystemAssistantSurface(
                onOpenApp = {
                    service.startActivity(
                        Intent(service, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                },
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Assistant session started")
    }

    private companion object {
        const val TAG = "SwitchboardSession"
    }
}

@Composable
private fun SystemAssistantSurface(onOpenApp: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Switchboard", style = MaterialTheme.typography.labelLarge)
                Text("Lucas is listening...", style = MaterialTheme.typography.headlineSmall)
                Text("Open the full conversation to use the V0.2 local wake flow and mock AI provider.")
                Button(onClick = onOpenApp) {
                    Text("Open Switchboard")
                }
            }
        }
    }
}
