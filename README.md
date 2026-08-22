# Switchboard V0.1

Switchboard is a provider-agnostic Android assistant shell. Version 0.1 proves the complete local path from a mock wake event to an assistant session, structured action routing, a visible conversation, and spoken output through Android Text to Speech.

The current provider is labelled **OpenAI (mock)** for the settings prototype. It is an offline fake: the app has no OpenAI SDK, network permission, endpoint, model name, account system, or API key.

## What works

- Kotlin and Jetpack Compose app targeting Android 16 (API 36)
- Configurable wake phrase, defaulting to `Hey Lucas`
- Runtime microphone permission flow
- Android-compliant microphone foreground-service declaration and notification
- UI-triggered `MockWakeWordProvider`
- Automatic mock response and Android TTS playback
- Additional text messages within the mock conversation
- `RoleManager.ROLE_ASSISTANT` request flow
- `VoiceInteractionService`, separate-process `VoiceInteractionSessionService`, metadata, and a registration-only `RecognitionService`
- Replaceable provider, wake-word, voice, and action interfaces
- Unit tests for wake events, provider responses, structured action routing, and the end-to-end controller flow

V0.1 does **not** record microphone audio or detect a real phrase. The foreground service and permission contract are present for the later on-device wake engine, while the current wake provider only responds to the debug button.

## Open in Android Studio

1. Install a current Android Studio release that supports Android Gradle Plugin 9.2.
2. Install **Android SDK Platform 36** and **Android SDK Build-Tools 36.0.0** from **Tools → SDK Manager**.
3. Choose **File → Open** and select this repository's root folder (the folder containing `settings.gradle.kts`).
4. Let Android Studio use its bundled JDK and finish Gradle sync.
5. Select the `app` run configuration and a physical device or emulator.

The command-line equivalents are:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install on a Samsung Galaxy Z Flip 5

1. On the phone, open **Settings → About phone → Software information** and tap **Build number** seven times.
2. Open **Settings → Developer options** and enable **USB debugging**.
3. Connect the phone, accept its computer authorization prompt, and verify it appears with `adb devices`.
4. Install the debug build:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. Launch **Switchboard** from the app drawer.

Android 14+ only permits a microphone foreground service to start from a user-visible state while microphone permission is active. Start it with the in-app toggle; do not expect Android to allow an arbitrary background restart.

## Run the V0.1 acceptance flow

1. Launch Switchboard.
2. Leave the wake phrase as **Hey Lucas**, or edit it.
3. Turn on **Enable Wake Word**.
4. Grant microphone permission. On Android 13+, also allow notifications if you want the listening notification visible in the notification drawer.
5. Tap **Set Switchboard as Default Assistant** and approve the Android system role dialog.
6. Tap **Trigger mock “Hey Lucas”**.
7. Confirm the conversation card displays **Lucas is listening...**.
8. Confirm the mock reply appears and is spoken by the device's Android TTS engine.
9. Optionally enter another message and tap **Send**.

If the Samsung role dialog is unavailable, check **Settings → Apps → Choose default apps → Digital assistant app** and select Switchboard. One UI wording varies by OS version and region.

If no speech is heard, confirm media volume is audible and an engine/language is installed under **Settings → General management → Text-to-speech**.

## Useful log filter

```bash
adb logcat -s SwitchboardWakeSvc SwitchboardWakeWord SwitchboardAssistant SwitchboardVoice SwitchboardRole SwitchboardSession
```

Expected messages include:

- `Wake word service started`
- `Wake word detected`
- `Assistant session started`
- `Voice provider activated`

## Project structure

```text
app/                         Compose UI, settings, services, Android role integration
core/assistant/              Provider-neutral assistant orchestration
core/wakeword/               WakeWordProvider and MockWakeWordProvider
core/actions/                ActionHandler, ActionRouter, and mock handler
providers/provider-api/      AiProvider, AssistantSession, and response/action models
providers/openai/            Offline OpenAI-labelled mock provider
voice/voice-api/             VoiceProvider contract
voice/android-tts/           Android TextToSpeech adapter
docs/                        Architecture, security, and roadmap
```

See [architecture](docs/architecture.md), [security](docs/security.md), and [roadmap](docs/roadmap.md) for the design boundary and planned integrations.

