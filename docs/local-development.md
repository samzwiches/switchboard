# Switchboard local backend connection

## SAM, DO THIS NOW

1. Open Terminal in `/Users/samz/Documents/GitHub/switchboard` and run:

   ```bash
   cd /Users/samz/Documents/GitHub/switchboard
   ./scripts/start-backend.sh
   ```

   Leave it running. If the script says the backend is already running, use that existing server. The command starts the existing Cloudflare Worker, not a new backend.

2. Install the new debug APK on your phone (or an emulator if you later install one). With one USB-debugging device connected, open a second Terminal and run:

   ```bash
   cd /Users/samz/Documents/GitHub/switchboard
   SWITCHBOARD_SDK="$(sed -n 's/^sdk.dir=//p' local.properties)"
   "$SWITCHBOARD_SDK/platform-tools/adb" devices
   "$SWITCHBOARD_SDK/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Accept the phone's USB debugging authorization prompt. This installs the newly built APK; opening an older downloaded APK will not apply these changes.

3. Open Switchboard. With no saved override, the Backend URL appears automatically:

   - Emulator: `http://10.0.2.2:8787`.
   - Physical Android phone: `http://192.168.0.195:8787` for the Mac's address at this build.

   No address entry is needed. If a previous address or blank override is saved, tap **Debug Settings → Use development default**.

4. Tap **TEST BACKEND**. Expected: **Connected**, **Backend v0.2**, **OpenAI configured**, **Model: gpt-5.6-luna**. This checks the backend, not OpenAI billing/quota or the key's validity. Select **OpenAI** for real assistant requests; Mock remains available.

5. For a physical phone, put the phone and Mac on the same Wi-Fi. Use the Mac LAN URL, not phone localhost or the emulator alias. Allow the Node backend through the macOS local-network/firewall prompt if shown. Guest Wi-Fi/client isolation or VPN routing may prevent LAN access.

6. If the Mac address changes, the startup script prints the new address. Rerun it and, in the second Terminal, run `./gradlew assembleDebug`, reinstall the APK, and tap **Use development default**. This refreshes both the phone default and its narrow HTTP allowlist. No production code edit is needed.

## Discovery and cause

- Existing backend: `backend/`, a TypeScript Cloudflare Worker.
- Existing direct command: `npm run dev` invokes Wrangler. The new root script supplies consistent binding/port and local environment setup.
- Port: `8787`, optionally overridden by `PORT` in ignored `backend/.env` or the process environment.
- Bind address: `0.0.0.0` for local emulator/phone access; no public deployment.
- App request: `POST /api/assistant`; connection test: `GET /health`.
- `backend/src/index.ts` reads `env.OPENAI_API_KEY`. The Worker alone sends it to the OpenAI Responses API.
- Original configuration example: `backend/.dev.vars.example`. Added `backend/.env.example` with empty key and `PORT=8787`.
- The old APK was built with `BuildConfig.SWITCHBOARD_BACKEND_URL = ""` because there was no Gradle/environment/local-property URL. The settings repository returned that empty default when no preference existed. No device was connected to inspect the installed app's private preferences directly.
- An ignored local `.env` now references the original ignored `.dev.vars` through `SWITCHBOARD_SECRET_FILE`. No actual key was copied, printed, or added to Git.

## Debug-only behavior

The startup script detects the Mac's default network interface and writes non-secret `SWITCHBOARD_DEBUG_PORT` and `SWITCHBOARD_DEBUG_LAN_URL` to ignored `local.properties`. Debug BuildConfig receives these addresses. The app detects standard Android emulators and chooses `10.0.2.2`; physical devices choose the LAN address. Explicit build-time and saved URLs still take precedence.

Only the debug variant receives the generated network security resource. It denies cleartext by default, allowing exactly the emulator address, loopback, and configured local development hosts. Release BuildConfig has empty development defaults; the release manifest keeps `usesCleartextTraffic=false` and has no debug network security resource reference.

Debug Settings retains manual URL editing and adds **TEST BACKEND**, **Use development default**, and useful missing-configuration instructions. Testing sends only GET /health and never calls OpenAI or transmits a key. Edited URLs must still be saved to use for conversations.

The existing Hey Lucas detector, Mock mode, TTS, assistant services, and Responses API conversation path are preserved.

## Reference

- [Android emulator networking](https://developer.android.com/studio/run/emulator-networking)
- [Android per-domain network security configuration](https://developer.android.com/privacy-and-security/security-config)

## Validation for this pass

- Backend started successfully using `./scripts/start-backend.sh`; a second invocation correctly identified the running server.
- Both `http://127.0.0.1:8787/health` and `http://192.168.0.195:8787/health` returned HTTP 200 and the expected v0.2/model/key-presence metadata.
- Backend TypeScript typecheck passed; **19 backend tests passed**, with fake upstream requests only.
- `./gradlew testDebugUnitTest assembleDebug lintDebug :app:processReleaseMainManifest :app:generateReleaseBuildConfig --console=plain` succeeded.
- **44 Android tests passed**, zero failures/errors, including emulator/phone/release defaults and health/error parsing.
- `assembleDebug` passed. APK: `/Users/samz/Documents/GitHub/switchboard/app/build/outputs/apk/debug/app-debug.apk`.
- Lint passed with **25 warnings and zero errors** (existing dependency/SDK upgrade notices and a redundant SDK check).
- APK scan found no actual OpenAI key bytes, key patterns, or `OPENAI_API_KEY`. Git-visible files also contained no actual key bytes.
- Verified the APK contains the narrow debug network resource. Verified the generated release manifest has cleartext disabled with no debug resource reference, and release BuildConfig contains neither emulator nor LAN development URLs.
- No emulator binary/AVD or connected Android device is available, so installation and on-device UI testing were not performed. Defaults were tested and verified in generated build artifacts.
- No public deployment and no new paid OpenAI calls were made. Health confirms local configuration, not OpenAI account quota. The prior live attempt's 429 result remains separate from this connection verification.

## Files changed or created

- `README.md`
- `app/build.gradle.kts`
- `app/src/debug/AndroidManifest.xml`
- `app/src/main/java/com/switchboard/app/MainActivity.kt`
- `app/src/main/java/com/switchboard/app/SwitchboardApplication.kt`
- `app/src/main/java/com/switchboard/app/settings/BackendDefaults.kt`
- `app/src/main/java/com/switchboard/app/settings/SwitchboardSettingsRepository.kt`
- `app/src/main/java/com/switchboard/app/ui/SwitchboardApp.kt`
- `app/src/test/java/com/switchboard/app/settings/BackendDefaultsTest.kt`
- `backend/.env.example`
- `backend/README.md`
- `backend/src/assistant.ts`
- `backend/test/index.test.ts`
- `docs/local-development.md`
- `providers/openai/src/main/java/com/switchboard/providers/openai/BackendHealthChecker.kt`
- `providers/openai/src/main/java/com/switchboard/providers/openai/OpenAiBackendProvider.kt`
- `providers/openai/src/test/java/com/switchboard/providers/openai/BackendHealthCheckerTest.kt`
- `scripts/start-backend.mjs`
- `scripts/start-backend.sh`

Local ignored configuration: `local.properties` now has detected debug port/LAN defaults; `backend/.env` contains a port and pointer to the existing secret file, not a copied key. Existing `.gitignore` already covers both. The original secret file was preserved. Concurrent `.vscode/settings.json` changes were not made by this work.
