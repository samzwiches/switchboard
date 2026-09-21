# Switchboard V0.2 — secure OpenAI backend pass

Switchboard is a Kotlin-only, provider-agnostic Android assistant shell. It keeps the continuous local **Hey Lucas** detector and mock provider, adds post-wake Android speech recognition, and introduces a real OpenAI provider through a small Cloudflare Worker. The OpenAI API key never enters the Android project or APK.

## Runtime architecture

```text
WakeWordForegroundService
  → DefaultWakeWordProvider
  → SherpaOnnxWakeWordDetector (local ambient wake audio)
  → AssistantController
  → AndroidSpeechInputEngine (one post-wake utterance)
  → shared message pipeline ← Compose text field
  → selected provider
       ├─ MockOpenAiProvider (offline)
       └─ OpenAiBackendProvider → Cloudflare Worker → OpenAI Responses API
  → conversation UI
  → ActionRouter
  → AndroidTtsVoiceProvider
  → wake detector rearm
```

`AssistantController` owns one sequential state flow: `Idle → Listening → Thinking → Speaking → Idle`, with a transient `Error` for recoverable failures before returning to `Idle`; the safe error message stays visible. Wake capture is stopped before post-wake dictation, remains stopped while the backend is processing and TTS is speaking, then rearms. Typed and spoken input both call the same provider/session method.

The existing Android assistant-role components remain registered: `RoleManager.ROLE_ASSISTANT`, `VoiceInteractionService`, `VoiceInteractionSessionService`, and the role-facing `RecognitionService`. The new app-owned speech adapter does not replace the role-facing registration service or sherpa-onnx.

## Providers

| Setting | Network | Credential location | Behavior |
|---|---:|---|---|
| **OpenAI (mock)** | No | None | Deterministic local responses for fallback and tests |
| **OpenAI** | Yes | Cloudflare Worker secret only | Bounded context sent to the Worker; real model response returned as text |

Select either provider under **Assistant settings**. Selecting a different provider cancels any active interaction, closes the current in-memory session, and uses the new provider for the next message. The app never silently falls back from OpenAI to mock.

If `SWITCHBOARD_BACKEND_URL` is missing, OpenAI remains selectable but clearly reports **Unavailable — backend URL is not configured** and returns a controlled configuration error.

## OpenAI implementation

The Worker calls:

```text
POST https://api.openai.com/v1/responses
model: gpt-5.6-luna
reasoning.effort: none
text.verbosity: low
max_output_tokens: 300
store: false
```

`gpt-5.6-luna` is configured once in `backend/wrangler.jsonc`. The Lucas instruction is backend-only and asks for natural, direct, usually concise spoken responses. The Android session retains at most 10 recent user/assistant messages and 12,000 characters, trimming complete turns; the Worker independently validates a maximum of 12 conversation messages and bounded text/body sizes.

The Worker returns only:

```json
{ "text": "assistant response" }
```

or a safe structured error. Neither side logs authorization headers, message text, or full conversations.

## Backend secret setup

Local Worker secrets belong in the ignored file:

```text
backend/.dev.vars
```

Format:

```dotenv
OPENAI_API_KEY=your_key_here
```

Configure an existing key through a secure local environment or the OpenAI Platform setup flow. Never paste it into chat. The value must not be printed, committed, placed in `local.properties`, or packaged in the APK.

For a deployed Worker, configure the secret interactively; never add it to `wrangler.jsonc`:

```bash
cd backend
npx wrangler secret put OPENAI_API_KEY
```

The local `.dev.vars` file is not uploaded automatically.

## Run the backend locally

```bash
cd backend
npm install
npm run typegen
npm run typecheck
npm test
npm run dev -- --ip 127.0.0.1 --port 8787
```

Verify health:

```bash
curl http://127.0.0.1:8787/health
```

Expected response:

```json
{ "ok": true }
```

The API endpoint is `POST /api/assistant`. Unsupported paths and methods are rejected. CORS is permissive for development, requests are size-limited, upstream calls time out, and OpenAI authentication/rate-limit/server failures are mapped to safe status codes.

## Configure the Android backend URL

In a debug build, enter the server address under **Assistant settings → Backend URL → Save backend URL**. This takes effect without rebuilding and clears the current conversation. Only this non-secret address is saved on Android.

To set a build-time default instead, add it to root `local.properties` (never add a key there):

```properties
SWITCHBOARD_BACKEND_URL=https://switchboard-assistant-api.your-subdomain.workers.dev
```

For a USB-connected device using the local Worker:

```bash
adb reverse tcp:8787 tcp:8787
```

and use:

```properties
SWITCHBOARD_BACKEND_URL=http://127.0.0.1:8787
```

The debug manifest permits cleartext traffic for local development. The main/release manifest keeps cleartext disabled. Changing the debug UI setting needs no rebuild. Changing the build-time default requires rebuilding.

## Deploy the Worker

Deployment is intentionally separate from building the Android app:

```bash
cd backend
npx wrangler login
npx wrangler secret put OPENAI_API_KEY
npm run deploy
```

Then save the returned HTTPS Worker URL in the debug app settings, or set the build-time default in root `local.properties`.

Do not treat this minimal unauthenticated endpoint as production-ready. Before public deployment, add user authentication, abuse controls/rate limiting, and an explicit privacy policy. CORS does not protect a mobile API from non-browser callers. No deployment was performed during this pass.

## Speech and conversation behavior

- Ambient wake detection remains continuous, local, and handled only by sherpa-onnx.
- After **Hey Lucas**, the wake detector releases the microphone before `AndroidSpeechInputEngine` starts.
- Android 12+ uses the on-device recognizer when the device exposes one; otherwise it uses the system speech service. The fallback service may process the post-wake utterance remotely.
- Recognition is one-shot with a 12-second upper bound. It never listens forever.
- Only the resulting transcript is sent to the selected AI provider.
- TTS and speech recognition never run concurrently; no barge-in is implemented.
- Conversation context stays in memory across typed and wake-initiated turns until **Clear**, provider switching, or service Stop. It is not persisted.
- After TTS, sherpa-onnx rearms. A new spoken turn currently requires **Hey Lucas** again.

## Android permissions

The intentional permissions are:

- `RECORD_AUDIO`
- `POST_NOTIFICATIONS`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `INTERNET`

`INTERNET` is used only by `OpenAiBackendProvider` after a typed or post-wake transcript is submitted. Ambient wake audio is never sent to the Worker or OpenAI.

## Build and test

Requirements: Android SDK Platform 36, Build-Tools 36.0.0, and a current JDK/Android Studio compatible with Android Gradle Plugin 9.2.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Backend validation:

```bash
cd backend
npm run typegen
npm run typecheck
npm test
npx wrangler deploy --dry-run
```

Tests use fake providers/transports/speech/wake/TTS implementations. They do not make paid OpenAI calls.

## Device checklist

1. Run or deploy the backend and verify `/health`.
2. Configure `SWITCHBOARD_BACKEND_URL` in root `local.properties`.
3. For a local backend on USB, run `adb reverse tcp:8787 tcp:8787`.
4. Build and install the debug APK.
5. Grant microphone and notification permissions.
6. Select **OpenAI**.
7. Enable **Wake Word** and confirm the foreground notification.
8. Confirm diagnostics show service running, microphone active, and wake detector armed.
9. Say **Hey Lucas**, then ask a normal question.
10. Confirm the transcript appears, state changes to **Thinking**, the response appears, and TTS speaks it.
11. Confirm the wake detector remains paused during TTS and rearms afterward.
12. Send a typed follow-up and confirm context is retained.
13. Tap **Clear** and confirm local conversation context is removed.
14. Switch to **OpenAI (mock)** and confirm the deterministic response still works.
15. Background and lock the phone while the foreground service is active; repeat the wake flow.
16. Tap notification **Stop** during listening and during a separate processing test; confirm speech/provider/TTS work is canceled and the microphone is released.

## Diagnostics and logs

The UI reports foreground service, wake microphone, wake detector/engine, speech recognizer, selected provider, provider configuration, assistant state, audio level, last wake, last speech, and last provider result. Provider configuration shows only **Configured**, **Missing backend URL**, or **Offline**.

Useful Logcat filter:

```bash
adb logcat -s SwitchboardWakeSvc SwitchboardWakeWord SwitchboardAssistant SwitchboardSpeech SwitchboardProvider SwitchboardVoice SwitchboardRole SwitchboardSession
```

Lifecycle logs include character counts, state changes, and safe error categories—not transcript text or secrets.

## Known limitations

- The Worker is minimal and unauthenticated; keep it local until authentication and abuse controls are added.
- The one controlled live smoke request reached the Worker/OpenAI path but returned a safe 429 rate-limit response. The Platform project needs usable API capacity before a real generated response can be confirmed.
- Physical microphone, lock-screen, OEM recognizer, and TTS behavior still require the device checklist.
- The system `SpeechRecognizer` fallback can use a remote recognition service; only sherpa wake detection is guaranteed local.
- No hands-free follow-up window or barge-in yet; each spoken turn requires the wake phrase.
- No persistent memory, streaming response, database, or remote action infrastructure is included.

See [architecture](docs/architecture.md), [security](docs/security.md), and [roadmap](docs/roadmap.md).

## Current checkout validation

See [the September 21 validation report](docs/v0.2-checkout-validation.md) for source restoration, changed files, current test results, and the live API blocker. Earlier work logs describe earlier sessions.
