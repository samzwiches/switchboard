const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
const MAX_BODY_BYTES = 32_768;
const MAX_MESSAGE_CHARS = 4_000;
const MAX_CONVERSATION_MESSAGES = 12;
const MAX_CONVERSATION_CHARS = 12_000;
const OPENAI_TIMEOUT_MS = 30_000;
const MAX_OUTPUT_TOKENS = 300;

const LUCAS_INSTRUCTIONS =
  "You are Lucas, the conversational assistant inside Switchboard. Respond naturally and " +
  "directly. Spoken responses should usually be concise because they will be read aloud. " +
  "Preserve conversational context. If the user asks for something that requires an Android " +
  "action, return the conversational response normally and allow the existing action architecture " +
  "to handle supported device actions.";

type ConversationRole = "user" | "assistant";

interface ConversationMessage {
  readonly role: ConversationRole;
  readonly text: string;
}

interface AssistantRequestBody {
  readonly message: string;
  readonly conversation: readonly ConversationMessage[];
}

interface BackendConfig {
  readonly apiKey: string;
  readonly model: string;
}

export type OpenAiFetch = (url: string, init: RequestInit) => Promise<Response>;

class SafeHttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export async function handleRequest(
  request: Request,
  config: BackendConfig,
  openAiFetch: OpenAiFetch,
): Promise<Response> {
  const requestId = crypto.randomUUID();
  const url = new URL(request.url);

  if (request.method === "OPTIONS") {
    return withCors(new Response(null, { status: 204 }), requestId);
  }

  if (url.pathname === "/health") {
    if (request.method !== "GET") {
      return errorResponse(405, "method_not_allowed", "Method not allowed.", requestId);
    }
    return jsonResponse({ ok: true }, 200, requestId);
  }

  if (url.pathname !== "/api/assistant") {
    return errorResponse(404, "not_found", "Not found.", requestId);
  }
  if (request.method !== "POST") {
    return errorResponse(405, "method_not_allowed", "Method not allowed.", requestId);
  }

  try {
    if (config.apiKey.trim().length === 0) {
      throw new SafeHttpError(503, "service_not_configured", "AI service is not configured.");
    }

    const body = await parseAssistantRequest(request);
    const input = [
      ...body.conversation.map((item) => ({ role: item.role, content: item.text })),
      { role: "user" as const, content: body.message },
    ];
    const signal = AbortSignal.timeout(OPENAI_TIMEOUT_MS);
    const openAiResponse = await openAiFetch(OPENAI_RESPONSES_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: config.model,
        instructions: LUCAS_INSTRUCTIONS,
        input,
        reasoning: { effort: "none" },
        text: { verbosity: "low" },
        max_output_tokens: MAX_OUTPUT_TOKENS,
        store: false,
      }),
      signal,
    });

    if (!openAiResponse.ok) {
      throw mapOpenAiStatus(openAiResponse.status);
    }

    let rawResponse: unknown;
    try {
      rawResponse = await openAiResponse.json();
    } catch {
      throw new SafeHttpError(502, "invalid_ai_response", "AI service returned an invalid response.");
    }

    const text = extractAssistantText(rawResponse);
    if (text === null) {
      throw new SafeHttpError(502, "empty_ai_response", "AI service returned no response text.");
    }

    return jsonResponse({ text }, 200, requestId);
  } catch (error) {
    if (error instanceof SafeHttpError) {
      console.error(JSON.stringify({
        event: "assistant_request_failed",
        requestId,
        code: error.code,
        status: error.status,
      }));
      return errorResponse(error.status, error.code, error.message, requestId);
    }

    const timedOut = error instanceof DOMException && error.name === "TimeoutError";
    const code = timedOut ? "ai_timeout" : "ai_unavailable";
    const status = timedOut ? 504 : 502;
    console.error(JSON.stringify({ event: "assistant_request_failed", requestId, code, status }));
    return errorResponse(
      status,
      code,
      timedOut ? "AI service timed out." : "AI service is unavailable.",
      requestId,
    );
  }
}

async function parseAssistantRequest(request: Request): Promise<AssistantRequestBody> {
  const contentType = request.headers.get("Content-Type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    throw new SafeHttpError(415, "unsupported_media_type", "Content-Type must be application/json.");
  }

  const rawText = await readLimitedBody(request, MAX_BODY_BYTES);
  let value: unknown;
  try {
    value = JSON.parse(rawText);
  } catch {
    throw new SafeHttpError(400, "invalid_json", "Request body must be valid JSON.");
  }
  if (!isRecord(value)) {
    throw new SafeHttpError(400, "invalid_request", "Request body must be an object.");
  }

  const message = typeof value.message === "string" ? value.message.trim() : "";
  if (message.length === 0) {
    throw new SafeHttpError(400, "invalid_message", "Message is required.");
  }
  if (message.length > MAX_MESSAGE_CHARS) {
    throw new SafeHttpError(413, "message_too_long", "Message is too long.");
  }

  const conversationValue = value.conversation ?? [];
  if (!Array.isArray(conversationValue) || conversationValue.length > MAX_CONVERSATION_MESSAGES) {
    throw new SafeHttpError(400, "invalid_conversation", "Conversation is invalid.");
  }

  const conversation: ConversationMessage[] = [];
  let conversationChars = 0;
  for (const item of conversationValue) {
    if (!isRecord(item)) {
      throw new SafeHttpError(400, "invalid_conversation", "Conversation is invalid.");
    }
    const role = item.role;
    const text = typeof item.text === "string" ? item.text.trim() : "";
    if ((role !== "user" && role !== "assistant") || text.length === 0 || text.length > MAX_MESSAGE_CHARS) {
      throw new SafeHttpError(400, "invalid_conversation", "Conversation is invalid.");
    }
    conversationChars += text.length;
    if (conversationChars > MAX_CONVERSATION_CHARS) {
      throw new SafeHttpError(413, "conversation_too_long", "Conversation is too long.");
    }
    conversation.push({ role, text });
  }

  return { message, conversation };
}

async function readLimitedBody(request: Request, maxBytes: number): Promise<string> {
  if (request.body === null) {
    throw new SafeHttpError(400, "missing_body", "Request body is required.");
  }
  const reader = request.body.getReader();
  const decoder = new TextDecoder();
  let received = 0;
  let result = "";
  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    received += chunk.value.byteLength;
    if (received > maxBytes) {
      await reader.cancel();
      throw new SafeHttpError(413, "request_too_large", "Request body is too large.");
    }
    result += decoder.decode(chunk.value, { stream: true });
  }
  return result + decoder.decode();
}

function extractAssistantText(value: unknown): string | null {
  if (!isRecord(value)) return null;

  if (typeof value.output_text === "string" && value.output_text.trim().length > 0) {
    return value.output_text.trim();
  }
  if (!Array.isArray(value.output)) return null;

  const parts: string[] = [];
  for (const item of value.output) {
    if (!isRecord(item) || !Array.isArray(item.content)) continue;
    for (const content of item.content) {
      if (!isRecord(content)) continue;
      if (content.type === "output_text" && typeof content.text === "string") {
        const text = content.text.trim();
        if (text.length > 0) parts.push(text);
      }
    }
  }
  return parts.length > 0 ? parts.join("\n") : null;
}

function mapOpenAiStatus(status: number): SafeHttpError {
  if (status === 429) {
    return new SafeHttpError(429, "rate_limited", "AI service is busy. Try again shortly.");
  }
  if (status === 401 || status === 403) {
    return new SafeHttpError(503, "ai_authentication_failed", "AI service is not configured correctly.");
  }
  if (status >= 500) {
    return new SafeHttpError(502, "ai_unavailable", "AI service is unavailable.");
  }
  return new SafeHttpError(502, "ai_request_failed", "AI service could not complete the request.");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function jsonResponse(value: unknown, status: number, requestId: string): Response {
  return withCors(Response.json(value, { status }), requestId);
}

function errorResponse(
  status: number,
  code: string,
  message: string,
  requestId: string,
): Response {
  return jsonResponse({ error: { code, message } }, status, requestId);
}

function withCors(response: Response, requestId: string): Response {
  const headers = new Headers(response.headers);
  headers.set("Access-Control-Allow-Origin", "*");
  headers.set("Access-Control-Allow-Headers", "Content-Type");
  headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  headers.set("Cache-Control", "no-store");
  headers.set("X-Request-Id", requestId);
  return new Response(response.body, { status: response.status, headers });
}
