import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { AiChatOptions, AiChatResult, AiMessage, AiProvider } from "../src/ai/types.ts";
import { estimateMessagesTokens } from "../src/budget/estimate.ts";
import { TokenBudget } from "../src/budget/ledger.ts";
import { utcDayStart } from "../src/budget/utc.ts";
import {
  AI_CHAT_LIMIT_REPLY,
  AI_GLOBAL_LIMIT_REPLY,
  SYSTEM_PROMPT,
  processPrivateText,
  type ProcessDeps,
} from "../src/chat/process.ts";
import { openChatStore, type ChatStore } from "../src/db/store.ts";

class FakeProvider implements AiProvider {
  readonly name = "fake";
  readonly model = "fake-model";
  calls = 0;
  lastOptions: AiChatOptions | undefined;

  constructor(private readonly impl: AiProvider["chat"]) {}

  async chat(
    messages: readonly AiMessage[],
    signal?: AbortSignal,
    options?: AiChatOptions,
  ): Promise<AiChatResult> {
    this.calls += 1;
    this.lastOptions = options;
    return await this.impl(messages, signal, options);
  }
}

function reply(text: string, inputTokens = 2, outputTokens = 3): AiChatResult {
  return { text, inputTokens, outputTokens };
}

describe("utcDayStart", () => {
  it("resets at 00:00 UTC", () => {
    expect(utcDayStart(Date.parse("2026-01-15T23:59:59.000Z"))).toBe(
      Date.parse("2026-01-15T00:00:00.000Z"),
    );
    expect(utcDayStart(Date.parse("2026-01-16T00:00:00.000Z"))).toBe(
      Date.parse("2026-01-16T00:00:00.000Z"),
    );
  });
});

describe("token budgets", () => {
  let dir: string;
  let store: ChatStore;

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), "tradexa-budget-"));
    store = openChatStore(join(dir, "tradexa.db"));
  });

  afterEach(() => {
    store.close();
    rmSync(dir, { recursive: true, force: true });
  });

  function promptEstimate(text: string): number {
    return estimateMessagesTokens([
      { content: SYSTEM_PROMPT },
      { content: text },
    ]);
  }

  function deps(
    provider: AiProvider | null,
    budget: TokenBudget,
    inflight = new Set<number>(),
  ): ProcessDeps {
    return {
      store,
      provider,
      budget,
      inflight,
      timeoutMs: 50,
      maxRetries: 0,
      delay: async () => {},
    };
  }

  it("blocks a chat at its UTC daily limit without calling the provider", async () => {
    const text = "hello";
    const estimated = promptEstimate(text);
    const now = Date.parse("2026-01-16T12:00:00.000Z");
    store.recordUsage({
      chatId: 1,
      provider: "fake",
      model: "fake-model",
      inputTokens: estimated,
      outputTokens: 8,
      createdAt: now - 1_000,
    });
    const provider = new FakeProvider(async () => reply("should not run"));
    const budget = new TokenBudget(
      store,
      {
        dailyGlobalTokens: 100_000,
        dailyChatTokens: estimated + 8,
        maxOutputTokens: 8,
      },
      () => now,
    );

    const result = await processPrivateText(1, text, deps(provider, budget));
    expect(result).toEqual({ status: "limited", reply: AI_CHAT_LIMIT_REPLY });
    expect(provider.calls).toBe(0);
    expect(store.getHistory(1)).toEqual([]);
  });

  it("blocks the bot at the global UTC daily limit", async () => {
    const text = "hello";
    const estimated = promptEstimate(text);
    const now = Date.parse("2026-01-16T12:00:00.000Z");
    store.recordUsage({
      chatId: 99,
      provider: "fake",
      model: "fake-model",
      inputTokens: estimated,
      outputTokens: 8,
      createdAt: now - 1_000,
    });
    const provider = new FakeProvider(async () => reply("should not run"));
    const budget = new TokenBudget(
      store,
      {
        dailyGlobalTokens: estimated + 8,
        dailyChatTokens: 20_000,
        maxOutputTokens: 8,
      },
      () => now,
    );

    const result = await processPrivateText(1, text, deps(provider, budget));
    expect(result).toEqual({ status: "limited", reply: AI_GLOBAL_LIMIT_REPLY });
    expect(provider.calls).toBe(0);
  });

  it("ignores yesterday's UTC usage after the daily reset", async () => {
    const text = "hello";
    const estimated = promptEstimate(text);
    const now = Date.parse("2026-01-16T00:30:00.000Z");
    store.recordUsage({
      chatId: 1,
      provider: "fake",
      model: "fake-model",
      inputTokens: 50_000,
      outputTokens: 50_000,
      createdAt: Date.parse("2026-01-15T23:59:00.000Z"),
    });
    const provider = new FakeProvider(async () => reply("fresh day"));
    const budget = new TokenBudget(
      store,
      {
        dailyGlobalTokens: estimated + 8,
        dailyChatTokens: estimated + 8,
        maxOutputTokens: 8,
      },
      () => now,
    );

    const result = await processPrivateText(1, text, deps(provider, budget));
    expect(result.status).toBe("ok");
    expect(provider.calls).toBe(1);
  });

  it("reserves budget so concurrent chats cannot overspend", async () => {
    const text = "hello";
    const estimated = promptEstimate(text);
    const maxOutputTokens = 8;
    const budget = new TokenBudget(store, {
      dailyGlobalTokens: estimated + maxOutputTokens,
      dailyChatTokens: 20_000,
      maxOutputTokens,
    });
    const inflight = new Set<number>();
    const provider = new FakeProvider(async () => reply("ok", 2, 2));

    const results = await Promise.all([
      processPrivateText(1, text, deps(provider, budget, inflight)),
      processPrivateText(2, text, deps(provider, budget, inflight)),
    ]);

    const statuses = results.map((result) => result.status).toSorted();
    expect(statuses).toEqual(["limited", "ok"]);
    expect(provider.calls).toBe(1);
    expect(results.some((result) => result.status === "limited" && result.reply === AI_GLOBAL_LIMIT_REPLY)).toBe(
      true,
    );
  });

  it("releases a reservation after a failed AI call", async () => {
    const text = "hello";
    const estimated = promptEstimate(text);
    const budget = new TokenBudget(store, {
      dailyGlobalTokens: estimated + 8,
      dailyChatTokens: estimated + 8,
      maxOutputTokens: 8,
    });
    const provider = new FakeProvider(async () => {
      if (provider.calls === 1) {
        throw new Error("AI request failed with HTTP 400.");
      }
      return reply("recovered");
    });

    const first = await processPrivateText(1, text, deps(provider, budget));
    const second = await processPrivateText(1, "hello", deps(provider, budget));

    expect(first.status).toBe("error");
    expect(second.status).toBe("ok");
    expect(provider.calls).toBe(2);
  });

  it("stores actual token usage and passes max_tokens to the provider", async () => {
    const provider = new FakeProvider(async () => reply("ok", 11, 4));
    const budget = new TokenBudget(store, {
      dailyGlobalTokens: 100_000,
      dailyChatTokens: 20_000,
      maxOutputTokens: 256,
    });

    const result = await processPrivateText(7, "count me", deps(provider, budget));
    expect(result.status).toBe("ok");
    expect(provider.lastOptions).toEqual({ maxOutputTokens: 256 });
    expect(store.getChatTokenUsageSince(7, 0)).toBe(15);
    expect(store.getTokenUsageSince(0)).toBe(15);
  });
});
