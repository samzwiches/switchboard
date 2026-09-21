# Architecture

## Design rule

Switchboard owns orchestration; providers, keyword engines, speech input, voices, and actions remain replaceable edges. The OpenAI credential boundary is the backend, never Android.

## Runtime flow

```text
WakeWordForegroundService
    ↓ starts, stops, renders notification state
DefaultWakeWordProvider
    ↓                         ↑
SherpaOnnxWakeWordDetector    Developer simulation
    ↓ local AudioRecord + KWS
WakeWordEvent.Detected
    ↓
AssistantController: Listening
    ↓ pauses sherpa microphone
AndroidSpeechInputEngine: one-shot transcript
    ↓
shared processMessage(text) ← Compose typed input
    ↓
provider selected when session starts
    ├─ MockOpenAiProvider
    └─ OpenAiBackendProvider
          ↓ HTTPS/JSON, no OpenAI secret
       Cloudflare Worker
          ↓ server-held OPENAI_API_KEY
       OpenAI POST /v1/responses (gpt-5.6-luna)
    ↓
AssistantResponse → conversation UI → ActionRouter
    ↓
AndroidTtsVoiceProvider
    ↓ completion/error callback
DefaultWakeWordProvider rearms sherpa
```

Ambient microphone audio reaches only the local sherpa detector. The Worker receives text only after an explicit typed submission or accepted wake followed by one-shot speech recognition.

## Inspected starting connection

Before this pass, the real sherpa detector and foreground service were already wired correctly, but `SwitchboardApplication` injected only `MockOpenAiProvider`. Wake handling sent an internal mock sentinel directly to the provider. The role-facing `SwitchboardRecognitionService` was—and remains—a registration placeholder that returns `ERROR_CLIENT`; no app-owned post-wake dictation call existed in the coordinator.

The implementation extends those boundaries instead of replacing them:

- the sherpa detector and `DefaultWakeWordProvider` are unchanged;
- the Android assistant-role services remain registered;
- `AndroidSpeechInputEngine` is a separate one-shot adapter used only after wake capture stops;
- typed and spoken text converge inside `AssistantController.processMessage`;
- both real and mock providers implement the existing `AiProvider`/`AssistantSession` contracts.

## Assistant state machine

```text
Idle (wake detector may be armed)
  → Wake event
  → Listening
  → transcript
  → Thinking
  → provider response
  → Speaking
  → TTS completion
  → Idle + wake detector rearm

Any recoverable stage failure
  → Error + user-safe message
  → wake detector rearm
```

Assistant `Idle` is intentionally distinct from wake detector `ListeningForWakeWord`. Diagnostics therefore no longer label the assistant active while it is merely waiting for **Hey Lucas**.

Only one interaction job may run. Duplicate wake events are ignored while it is active. Stop, Clear, or provider switching cancels that job, stops speech/TTS, closes the provider session, and releases or rearms the wake detector as appropriate.

## Speech/TTS microphone ownership

1. Sherpa owns `AudioRecord` while waiting for the wake phrase.
2. Accepted detection stops sherpa capture.
3. `AndroidSpeechInputEngine` performs a bounded, one-shot recognition request.
4. Speech recognition is destroyed before provider processing/TTS.
5. TTS suspends until Android reports completion or failure.
6. Only after TTS completes does `DefaultWakeWordProvider` rearm sherpa.

On Android 12+, the adapter prefers an available on-device recognizer. Otherwise it uses the system recognizer, which may use a remote speech service. The role-facing `RecognitionService` remains separate.

## Provider selection and context

`SwitchboardSettingsRepository` persists only a provider ID. The app graph resolves that ID to one of two providers:

- `mock-openai` → `MockOpenAiProvider`
- `openai` → `OpenAiBackendProvider`

An `AssistantSession` captures the provider chosen when it starts. A provider change cancels any active interaction and clears the current session before the next request, preventing mixed-provider context.

The real session holds at most 10 recent user/assistant messages. Typed and wake-initiated turns share this session until Clear, provider switching, or Stop. The Worker performs its own independent validation and maximum limits. No context is written to disk.

## Backend contract

```text
POST /api/assistant
{
  "message": "current user text",
  "conversation": [
    { "role": "user", "text": "recent message" },
    { "role": "assistant", "text": "recent response" }
  ]
}

200
{ "text": "assistant response" }
```

The Worker validates content type, body bytes, per-message length, total conversation length, roles, and message count. It calls the Responses API with `store: false`, low verbosity, no reasoning effort, a 300-token output ceiling, and a 30-second upstream timeout. OpenAI error bodies and credentials are never forwarded.

Safe backend errors use `{ "error": { "code": string, "message": string } }`. Android maps configuration, authentication, rate-limit, timeout, network, malformed-response, and unavailable categories into short user messages.

## Foreground service and notification

The service still owns the visible microphone foreground lifecycle and `START_NOT_STICKY` behavior. Its notification combines wake and assistant state:

- waiting: **Switchboard is listening / Waiting for “Hey Lucas”**
- dictation: **Lucas is listening**
- backend request: **Lucas is thinking**
- TTS: **Lucas is responding**
- error: **Switchboard needs attention**

The existing notification Stop action cancels speech/provider/TTS work, releases sherpa, removes the notification, and stops the service.
