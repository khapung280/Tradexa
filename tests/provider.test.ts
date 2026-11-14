import { afterEach, describe, expect, it, vi } from "vitest";
import { chatCompletionsUrl, createOpenAiCompatibleProvider } from "../src/ai/openaiCompatible.ts";
import { GEMINI_OPENAI_BASE_URL, readAiConfig } from "../src/config/env.ts";

const sampleApiKey = `AIzaSy${"B".repeat(33)}`;
const geminiChatCompletionsUrl =
  "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("chatCompletionsUrl", () => {
  it("builds the Gemini OpenAI-compatible chat completions endpoint", () => {
    expect(chatCompletionsUrl(GEMINI_OPENAI_BASE_URL)).toBe(geminiChatCompletionsUrl);
    expect(chatCompletionsUrl(`${GEMINI_OPENAI_BASE_URL}/`)).toBe(geminiChatCompletionsUrl);
  });

  it("uses the normalized env base URL", () => {
    const ai = readAiConfig({
      AI_API_KEY: sampleApiKey,
      AI_BASE_URL: "https://generativelanguage.googleapis.com/v1beta/openai/",
      AI_MODEL: "gemini-2.0-flash",
    });
    expect(ai?.baseUrl).toBe(GEMINI_OPENAI_BASE_URL);
    expect(chatCompletionsUrl(ai?.baseUrl ?? "")).toBe(geminiChatCompletionsUrl);
  });
});

describe("OpenAiCompatibleProvider request", () => {
  it("POSTs the model and messages to chat/completions with a bearer token", async () => {
    const fetchMock = vi.fn(async () => {
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: "ok" } }],
          usage: { prompt_tokens: 1, completion_tokens: 2 },
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    const provider = createOpenAiCompatibleProvider({
      apiKey: sampleApiKey,
      baseUrl: GEMINI_OPENAI_BASE_URL,
      model: "gemini-2.0-flash",
    });
    const messages = [{ role: "user" as const, content: "hello" }];
    const result = await provider.chat(messages);

    expect(result).toEqual({ text: "ok", inputTokens: 1, outputTokens: 2 });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe(geminiChatCompletionsUrl);
    expect(init?.method).toBe("POST");
    const headers = new Headers(init?.headers);
    expect(headers.get("Content-Type")).toBe("application/json");
    expect(headers.get("Authorization")).toBe(`Bearer ${sampleApiKey}`);
    expect(JSON.parse(String(init?.body))).toEqual({
      model: "gemini-2.0-flash",
      messages,
    });
  });

  it("includes max_tokens when an output budget is provided", async () => {
    const fetchMock = vi.fn(async () => {
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: "ok" } }],
          usage: { prompt_tokens: 1, completion_tokens: 2 },
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    const provider = createOpenAiCompatibleProvider({
      apiKey: sampleApiKey,
      baseUrl: GEMINI_OPENAI_BASE_URL,
      model: "gemini-2.0-flash",
    });
    await provider.chat([{ role: "user", content: "hello" }], AbortSignal.timeout(1_000), {
      maxOutputTokens: 256,
    });

    const body = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body));
    expect(body.max_tokens).toBe(256);
  });
});
