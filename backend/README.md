# Switchboard assistant Worker

Minimal TypeScript Cloudflare Worker used as the credential boundary between the Android app and OpenAI.

## Endpoints

- `GET /health` → health/version/model/key-presence metadata
- `POST /api/assistant` with `{ "message": string, "conversation": [{ "role": "user" | "assistant", "text": string }] }`

The assistant endpoint calls the OpenAI Responses API with `gpt-5.6-luna` and returns `{ "text": string }`. Inputs, conversation length, request bytes, upstream timeout, and output tokens are bounded.

## Local setup

From the repository root, use the existing Worker through:

```bash
./scripts/start-backend.sh
```

Default port: **8787**. Bind address: **0.0.0.0** for local LAN/emulator access. The script prints both Android addresses and refreshes debug build defaults in ignored `local.properties`. It does not deploy anything.

The Worker reads `env.OPENAI_API_KEY`. The launcher can load the key from backend `.env`, `.dev.vars`, an existing file named by `SWITCHBOARD_SECRET_FILE`, or the process environment. The environment key takes priority. All actual secret files are ignored; `.env.example` contains only empty placeholders and `PORT=8787`. Never put a key in Android configuration, source, logs, or `wrangler.jsonc`.

`GET /health` reports service `switchboard`, version `0.2`, boolean `openaiConfigured`, and the configured model, alongside `ok`. It does not call OpenAI, validate the key, or expose its value.

Run local unit tests without paid API calls:

```bash
cd backend
npm run typecheck
npm test
```

## Deployment

```bash
npx wrangler login
npx wrangler secret put OPENAI_API_KEY
npm run deploy
```

The secret command is interactive and stores an encrypted Worker secret. Local `.dev.vars` values are not deployed automatically.

This endpoint has development CORS but no caller authentication. Add identity, rate limiting/abuse controls, and production monitoring before exposing it publicly.
