# Switchboard assistant Worker

Minimal TypeScript Cloudflare Worker used as the credential boundary between the Android app and OpenAI.

## Endpoints

- `GET /health` → `{ "ok": true }`
- `POST /api/assistant` with `{ "message": string, "conversation": [{ "role": "user" | "assistant", "text": string }] }`

The assistant endpoint calls the OpenAI Responses API with `gpt-5.6-luna` and returns `{ "text": string }`. Inputs, conversation length, request bytes, upstream timeout, and output tokens are bounded.

## Local setup

```bash
cp .dev.vars.example .dev.vars
# Add OPENAI_API_KEY to .dev.vars without committing it.
npm ci
npm run typegen
npm run typecheck
npm test
npm run dev
```

To reuse an existing secret file without copying it into this checkout:

```bash
npm run dev -- --ip 127.0.0.1 --env-file /absolute/path/to/existing/.dev.vars
```

Or supply `OPENAI_API_KEY` through the process environment. Do not put its value in shell command arguments.

`.dev.vars` and `.env*` are ignored. The key must not be added to source, `wrangler.jsonc`, Android configuration, or logs.

## Deployment

```bash
npx wrangler login
npx wrangler secret put OPENAI_API_KEY
npm run deploy
```

The secret command is interactive and stores an encrypted Worker secret. Local `.dev.vars` values are not deployed automatically.

This endpoint has development CORS but no caller authentication. Add identity, rate limiting/abuse controls, and production monitoring before exposing it publicly.
