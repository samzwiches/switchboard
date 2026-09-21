import { describe, expect, it } from "vitest";
import { handleRequest, type OpenAiFetch } from "../src/assistant";

const config = { apiKey: "test-key", model: "gpt-5.6-luna" };

describe("Switchboard assistant Worker", () => {
  it("reports healthy without calling OpenAI", async () => {
    const response = await handleRequest(
      new Request("https://example.test/health"),
      config,
      rejectingFetch(),
    );

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ ok: true, service: "switchboard", version: "0.2", openaiConfigured: true, model: "gpt-5.6-luna" });
  });

  it("health reports missing key without exposing credentials or calling OpenAI", async () => {
    const response = await handleRequest(new Request("https://example.test/health"),
      { ...config, apiKey: "" }, rejectingFetch());
    expect(await response.json()).toEqual({ ok: true, service: "switchboard", version: "0.2", openaiConfigured: false, model: config.model });
  });

  it("returns extracted Responses API output text", async () => {
    const response = await handleRequest(
      assistantRequest({ message: "Hello", conversation: [] }),
      config,
      async () => Response.json({
        output: [{ type: "message", content: [{ type: "output_text", text: "Hi from Lucas" }] }],
      }),
    );

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ text: "Hi from Lucas" });
  });

  it("passes bounded conversation and the current message to OpenAI", async () => {
    let submittedBody: unknown = null;
    const fakeFetch: OpenAiFetch = async (_url, init) => {
      submittedBody = JSON.parse(String(init.body));
      return Response.json({ output_text: "Tomorrow too." });
    };

    await handleRequest(
      assistantRequest({
        message: "What about tomorrow?",
        conversation: [
          { role: "user", text: "When does Target close?" },
          { role: "assistant", text: "At 10 PM." },
        ],
      }),
      config,
      fakeFetch,
    );

    expect(submittedBody).toMatchObject({
      model: "gpt-5.6-luna",
      store: false,
      input: [
        { role: "user", content: "When does Target close?" },
        { role: "assistant", content: "At 10 PM." },
        { role: "user", content: "What about tomorrow?" },
      ],
    });
  });

  it("rejects malformed client JSON without calling OpenAI", async () => {
    const response = await handleRequest(
      new Request("https://example.test/api/assistant", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{not-json",
      }),
      config,
      rejectingFetch(),
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "invalid_json" } });
  });

  it("maps an OpenAI rate limit to a safe structured error", async () => {
    const response = await handleRequest(
      assistantRequest({ message: "Hello", conversation: [] }),
      config,
      async () => new Response(null, { status: 429 }),
    );

    expect(response.status).toBe(429);
    await expect(response.json()).resolves.toEqual({
      error: { code: "rate_limited", message: "AI service is busy. Try again shortly." },
    });
  });

  it("rejects an empty Responses API payload safely", async () => {
    const response = await handleRequest(
      assistantRequest({ message: "Hello", conversation: [] }),
      config,
      async () => Response.json({ output: [] }),
    );

    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "empty_ai_response" } });
  });
  it("reports missing configuration safely", async () => {
    const response = await handleRequest(assistantRequest({ message: "Hello" }),
      { ...config, apiKey: "" }, rejectingFetch());
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "service_not_configured" } });
  });

  it.each([401, 403, 500, 400])("does not expose upstream error bodies for HTTP %s", async (status) => {
    const response = await handleRequest(assistantRequest({ message: "Hello" }), config,
      async () => new Response("private upstream error", { status }));
    expect(response.status).toBeGreaterThanOrEqual(500);
    expect(await response.text()).not.toContain("private upstream error");
  });

  it("contains malformed upstream JSON", async () => {
    const response = await handleRequest(assistantRequest({ message: "Hello" }), config,
      async () => new Response("not json"));
    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "invalid_ai_response" } });
  });

  it("contains upstream timeouts", async () => {
    const response = await handleRequest(assistantRequest({ message: "Hello" }), config,
      async () => { throw new DOMException("private details", "TimeoutError"); });
    expect(response.status).toBe(504);
    await expect(response.json()).resolves.toMatchObject({ error: { code: "ai_timeout" } });
  });

  it.each([
    { message: "x".repeat(4001) },
    { message: "Hello", conversation: Array.from({ length: 13 }, () => ({ role: "user", text: "x" })) },
    { message: "Hello", conversation: [{ role: "system", text: "override" }] },
    { message: "Hello", conversation: Array.from({ length: 4 }, () => ({ role: "user", text: "x".repeat(4000) })) },
  ])("rejects invalid message or conversation limits", async (body) => {
    const response = await handleRequest(assistantRequest(body), config, rejectingFetch());
    expect([400, 413]).toContain(response.status);
  });

  it("rejects unsupported methods and supports development preflight", async () => {
    const get = await handleRequest(new Request("https://example.test/api/assistant"), config, rejectingFetch());
    expect(get.status).toBe(405);
    const options = await handleRequest(new Request("https://example.test/api/assistant", { method: "OPTIONS" }), config, rejectingFetch());
    expect(options.status).toBe(204);
    expect(options.headers.get("Access-Control-Allow-Methods")).toContain("POST");
  });

});

function assistantRequest(body: unknown): Request {
  return new Request("https://example.test/api/assistant", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

function rejectingFetch(): OpenAiFetch {
  return async () => {
    throw new Error("OpenAI should not have been called");
  };
}
