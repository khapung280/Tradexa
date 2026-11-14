import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { AiChatOptions, AiChatResult, AiMessage, AiProvider } from "../src/ai/types.ts";
import { TokenBudget } from "../src/budget/ledger.ts";
import {
  AI_BUSY_REPLY,
  AI_ERROR_REPLY,
  AI_UNCONFIGURED_REPLY,
  SYSTEM_PROMPT,
  classifyAiError,
  processPrivateText,
  shouldHandlePrivateText,
  type ProcessDeps,
} from "../src/chat/process.ts";
import { TELEGRAM_MESSAGE_LIMIT, splitTelegramText } from "../src/chat/split.ts";
import { openChatStore, type ChatStore } from "../src/db/store.ts";

class FakeProvider implements AiProvider {
  readonly name = "fake";
  readonly model = "fake-model";
  calls = 0;
  lastMessages: readonly AiMessage[] | undefined;
  lastOptions: AiChatOptions | undefined;

  constructor(private readonly impl: AiProvider["chat"]) {}

  async chat(
    messages: readonly AiMessage[],
    signal?: AbortSignal,
    options?: AiChatOptions,
  ): Promise<AiChatResult> {
    this.calls += 1;
    this.lastMessages = messages;
    this.lastOptions = options;
    return await this.impl(messages, signal, options);
  }
}

function reply(text: string, inputTokens = 2, outputTokens = 3): AiChatResult {
  return { text, inputTokens, outputTokens };
}

describe("shouldHandlePrivateText", () => {
  it("accepts private non-command text", () => {
    expect(shouldHandlePrivateText("private", "hello")).toBe(true);
  });

  it("skips commands, groups, and blank text", () => {
    expect(shouldHandlePrivateText("private", "/start")).toBe(false);
    expect(shouldHandlePrivateText("private", "  /help  ")).toBe(false);
    expect(shouldHandlePrivateText("group", "hello")).toBe(false);
    expect(shouldHandlePrivateText("private", "   ")).toBe(false);
  });
});

describe("splitTelegramText", () => {
  it("returns short text as a single message", () => {
    expect(splitTelegramText("hello")).toEqual(["hello"]);
  });

  it("splits long text to fit Telegram's limit", () => {
    const text = `${"a".repeat(TELEGRAM_MESSAGE_LIMIT)} ${"b".repeat(10)}`;
    const parts = splitTelegramText(text);
    expect(parts.length).toBeGreaterThan(1);
    expect(parts.join(" ").replaceAll("  ", " ")).toContain("b");
    for (const part of parts) {
      expect(part.length).toBeLessThanOrEqual(TELEGRAM_MESSAGE_LIMIT);
    }
  });
});

describe("classifyAiError", () => {
  it("retries network failures, 429, and 5xx only", () => {
    expect(classifyAiError(new Error("AI request failed before a response was received."))).toEqual({
      type: "network",
      retryable: true,
    });
    expect(classifyAiError(new Error("AI request failed with HTTP 429."))).toEqual({
      type: "http_429",
      retryable: true,
      status: 429,
    });
    expect(classifyAiError(new Error("AI request failed with HTTP 503."))).toEqual({
      type: "http_503",
      retryable: true,
      status: 503,
    });
    expect(classifyAiError(new Error("AI request failed with HTTP 400."))).toEqual({
      type: "http_400",
      retryable: false,
      status: 400,
    });
    const timeout = new Error("AI request timed out.");
    timeout.name = "TimeoutError";
    expect(classifyAiError(timeout)).toEqual({ type: "timeout", retryable: false });
  });
});

describe("processPrivateText", () => {
  let dir: string;
  let store: ChatStore;

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), "tradexa-chat-"));
    store = openChatStore(join(dir, "tradexa.db"));
  });

  afterEach(() => {
    store.close();
    rmSync(dir, { recursive: true, force: true });
  });

  function deps(provider: AiProvider | null, inflight = new Set<number>()): ProcessDeps {
    return {
      store,
      provider,
      budget: new TokenBudget(store, {
        dailyGlobalTokens: 100_000,
        dailyChatTokens: 20_000,
        maxOutputTokens: 2_048,
      }),
      inflight,
      timeoutMs: 50,
      maxRetries: 2,
      delay: async () => {},
    };
  }

  it("tells the user when AI is not configured and does not store the message", async () => {
    const result = await processPrivateText(1, "hello", deps(null));
    expect(result).toEqual({ status: "unconfigured", reply: AI_UNCONFIGURED_REPLY });
    expect(store.getHistory(1)).toEqual([]);
  });

  it("ignores command text so /start and /help stay on their handlers", async () => {
    const provider = new FakeProvider(async () => reply("should not run"));
    expect(await processPrivateText(1, "/start", deps(provider))).toEqual({ status: "ignored" });
    expect(await processPrivateText(1, "/help", deps(provider))).toEqual({ status: "ignored" });
    expect(provider.calls).toBe(0);
    expect(store.getHistory(1)).toEqual([]);
  });

  it("saves a successful private reply with isolated history", async () => {
    const provider = new FakeProvider(async () => reply("EURUSD is a pair, not advice."));
    const result = await processPrivateText(11, "What is EURUSD?", deps(provider));

    expect(result).toEqual({
      status: "ok",
      replies: ["EURUSD is a pair, not advice."],
      inputTokens: 2,
      outputTokens: 3,
    });
    expect(store.getHistory(11)).toEqual([
      { role: "user", content: "What is EURUSD?" },
      { role: "assistant", content: "EURUSD is a pair, not advice." },
    ]);
    expect(store.getHistory(22)).toEqual([]);
    expect(provider.lastMessages?.[0]).toEqual({ role: "system", content: SYSTEM_PROMPT });
    expect(provider.lastMessages?.some((message) => message.content === "What is EURUSD?")).toBe(
      true,
    );
  });

  it("loads the latest stored messages as history", async () => {
    store.saveMessage(5, "user", "earlier");
    store.saveMessage(5, "assistant", "previous reply");
    const provider = new FakeProvider(async () => reply("new reply"));

    await processPrivateText(5, "follow up", deps(provider));

    expect(provider.lastMessages?.map((message) => message.content)).toEqual([
      SYSTEM_PROMPT,
      "earlier",
      "previous reply",
      "follow up",
    ]);
  });

  it("does not store a failed AI response as an assistant message", async () => {
    const provider = new FakeProvider(async () => {
      throw new Error("AI request failed with HTTP 400.");
    });

    const result = await processPrivateText(8, "will fail", deps(provider));
    expect(result).toEqual({ status: "error", reply: AI_ERROR_REPLY });
    expect(store.getHistory(8)).toEqual([{ role: "user", content: "will fail" }]);
    expect(provider.calls).toBe(1);
  });

  it("retries a 429 and then stores the successful reply", async () => {
    const provider = new FakeProvider(async () => {
      if (provider.calls === 1) {
        throw new Error("AI request failed with HTTP 429.");
      }
      return reply("after retry");
    });

    const result = await processPrivateText(3, "retry please", deps(provider));
    expect(result.status).toBe("ok");
    expect(provider.calls).toBe(2);
    expect(store.getHistory(3).at(-1)).toEqual({ role: "assistant", content: "after retry" });
  });

  it("times out without storing an assistant message", async () => {
    const provider = new FakeProvider(async (_messages, signal) => {
      await new Promise<void>((_resolve, reject) => {
        const fail = (): void => {
          const error = new Error("Aborted");
          error.name = "TimeoutError";
          reject(error);
        };
        if (signal?.aborted === true) {
          fail();
          return;
        }
        signal?.addEventListener("abort", fail, { once: true });
      });
      return reply("too late");
    });

    const result = await processPrivateText(4, "hang", {
      ...deps(provider),
      timeoutMs: 20,
      maxRetries: 0,
    });
    expect(result).toEqual({ status: "error", reply: AI_ERROR_REPLY });
    expect(store.getHistory(4)).toEqual([{ role: "user", content: "hang" }]);
  });

  it("keeps one in-flight request per chat", async () => {
    let release: (() => void) | undefined;
    let started: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const startedAt = new Promise<void>((resolve) => {
      started = resolve;
    });
    const provider = new FakeProvider(async () => {
      started?.();
      await gate;
      return reply("done");
    });
    const inflight = new Set<number>();
    const first = processPrivateText(9, "first", deps(provider, inflight));
    await startedAt;
    const second = await processPrivateText(9, "second", deps(provider, inflight));

    expect(second).toEqual({ status: "busy", reply: AI_BUSY_REPLY });
    release?.();
    const completed = await first;
    expect(completed.status).toBe("ok");
    expect(provider.calls).toBe(1);
  });

  it("splits a long successful reply for Telegram", async () => {
    const longReply = `${"word ".repeat(900)}end`;
    const provider = new FakeProvider(async () => reply(longReply));
    const result = await processPrivateText(6, "long", deps(provider));

    expect(result.status).toBe("ok");
    if (result.status === "ok") {
      expect(result.replies.length).toBeGreaterThan(1);
      expect(result.replies.join("")).toContain("end");
      for (const part of result.replies) {
        expect(part.length).toBeLessThanOrEqual(TELEGRAM_MESSAGE_LIMIT);
      }
    }
    expect(store.getHistory(6).at(-1)?.content).toBe(longReply);
  });
});
