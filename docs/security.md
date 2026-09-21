# Security and privacy

## Credential boundary

`OPENAI_API_KEY` exists only as a backend secret:

- local development: ignored `backend/.dev.vars`;
- deployed Worker: encrypted secret set with `wrangler secret put OPENAI_API_KEY`.

It is not stored in Android source, resources, assets, `BuildConfig`, Gradle properties, `local.properties`, SharedPreferences, DataStore, native libraries, diagnostics, or logs. The Android APK contains only the non-secret `SWITCHBOARD_BACKEND_URL`.

Never add the key to `wrangler.jsonc`; that file is version-controlled. Never copy a reusable provider key into a mobile client. A production deployment should keep this server-side boundary and add authenticated, authorized application users.

## Audio and transcript boundaries

### Ambient wake audio

The foreground service sends 16 kHz PCM only to the bundled local sherpa-onnx keyword model. Ambient frames are held in memory, not retained, transcribed, or uploaded.

### Post-wake speech

After sherpa accepts **Hey Lucas**, wake capture stops before Android `SpeechRecognizer` begins. On-device recognition is preferred where Android exposes it. The system recognizer fallback may send that one post-wake utterance to its configured remote recognition service.

Only the returned transcript—not raw audio—is sent to the Switchboard Worker. Speech recognition is destroyed before TTS, preventing direct TTS feedback.

### OpenAI text

The Worker sends bounded recent text context and the current message to the Responses API with `store: false`. Application logs contain only lifecycle names, character counts, HTTP categories, and request IDs. They do not contain complete prompts, transcripts, responses, authorization headers, or secret values.

## Network configuration

The main manifest adds `INTERNET` intentionally for the backend provider and keeps `android:usesCleartextTraffic="false"`. The debug manifest permits cleartext only to support a local Wrangler server during development; release builds require HTTPS.

No network request occurs during ambient wake listening. Network activity begins only after the user submits typed text or post-wake speech produces a transcript and **OpenAI** is selected. **OpenAI (mock)** remains offline.

## Backend safeguards

The Worker:

- reads `OPENAI_API_KEY` only from its runtime environment;
- requires JSON and rejects unsupported methods/routes;
- streams and caps the incoming body before parsing;
- bounds current message, history entries, total context, and output tokens;
- uses a fixed OpenAI endpoint and configured model;
- sets an upstream timeout;
- parses output text defensively;
- maps auth, rate-limit, timeout, malformed, and server failures to safe responses;
- logs only structured safe metadata;
- returns `Cache-Control: no-store`.

## Current deployment limitation

The minimal endpoint has development CORS but no caller authentication. CORS is not access control for native clients. A public deployment would allow an unauthenticated caller to consume the project’s OpenAI quota.

Therefore this pass leaves the Worker ready to deploy but does not deploy it. Before public exposure, add:

1. user authentication and short-lived app sessions;
2. per-user and per-device rate limits/quotas;
3. abuse detection and cost alerts;
4. an explicit transcript privacy/retention policy;
5. production monitoring with transcript redaction;
6. key rotation and incident response procedures.

## Android permissions

- `RECORD_AUDIO`: local wake capture and one-shot post-wake speech input.
- `POST_NOTIFICATIONS`: visible foreground-service notification on Android 13+.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE`: continuous local wake capture under an explicit foreground service.
- `INTERNET`: text-only backend requests for the real provider.

No contacts, SMS, phone, calendar, alarms, accessibility, location, storage, or broad package permissions are added.

## Exported Android components

The launcher activity is exported. The microphone foreground service is not. Voice interaction and recognition services remain exported only because Android system services bind to them, and retain the platform signature binding permissions `BIND_VOICE_INTERACTION` or `BIND_SPEECH_RECOGNITION`.
