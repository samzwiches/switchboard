# Roadmap

## V0.1 — runnable shell (complete)

- Compose settings and conversation UI
- mock OpenAI-labelled provider
- configurable wake phrase and mock wake event
- assistant role request and voice interaction service registration
- modern microphone foreground-service contract
- Android TTS behind `VoiceProvider`
- structured action routing with a mock handler
- unit and build validation

## V0.2 — conversational backend pass (this repository)

- Kotlin `AudioRecord` adapter behind `WakeWordDetector`
- sherpa-onnx English open-vocabulary keyword spotting with bundled local assets
- real and simulated detections converging on `WakeWordEvent`
- microphone level and explicit diagnostics state
- detector pause during the assistant/TTS phase and automatic rearm
- dynamic foreground notification and release-safe Stop action
- coordination, debounce, error, lifecycle, and assistant-path unit tests
- one-shot post-wake Android speech input behind a replaceable contract
- secure Cloudflare Worker credential boundary and OpenAI Responses API provider
- mock/OpenAI selection, bounded in-memory context, safe network errors, and cancellation

## V0.2.1 — Galaxy Z Flip 5 hardware validation and tuning

Run the documented acceptance flow on the target Samsung device and record:

- One UI / Android version and assistant chooser behavior;
- role retention after reboot and after app update;
- microphone and notification permission variants;
- foreground notification behavior with the phone open, folded, locked, and battery optimized;
- TTS engine/language availability;
- long-press power or gesture invocation of the `VoiceInteractionSession`;
- log evidence for microphone, detector, wake, assistant, and rearm transitions;
- false-accept and false-reject observations in quiet rooms, TV/music, a car, and barn/show noise;
- battery and thermal impact over a one-hour folded/locked listening run;
- threshold/score adjustments based on those results.

Any OEM-specific result should become a repeatable device checklist or instrumentation test before tuning is considered complete.

## V0.3 — authenticated backend and hands-free follow-up

Add real user/session authentication, per-user rate limits and budgets, a short hands-free follow-up window, streaming response UX, and production deployment monitoring. Keep the provider key server-side.

## V0.4 — alternative voice and barge-in

- Chatterbox or Kokoro evaluation behind `VoiceProvider`;
- barge-in and audio-focus behavior;
- language/voice selection and downloads.

## V0.5 — actions and trust

Add one low-risk integration first, with typed schemas, explicit confirmations, and an audit trail. Candidate sequence: Home Assistant sandbox action, Tasker handoff, Maps intent, then media providers. Avoid broad arbitrary-intent execution.

## Later

- provider and model picker;
- saved per-provider settings;
- local model adapter;
- accessibility and foldable-specific layout pass;
- testable plugin discovery/registration;
- optional accounts or sync only after a concrete cross-device need exists.
