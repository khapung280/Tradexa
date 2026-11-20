import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { AiChatOptions, AiMessage, AiProvider } from "../src/ai/types.ts";
import { ANALYSIS_EXPLANATION_PROMPT } from "../src/analysis/explain.ts";
import { TokenBudget } from "../src/budget/ledger.ts";
import { TELEGRAM_MESSAGE_LIMIT } from "../src/chat/split.ts";
import { buildAnalyzeReplies, renderAnalyzeReply } from "../src/commands/analyze.ts";
import { openChatStore, type ChatStore } from "../src/db/store.ts";
import { NATIVE_ANALYSIS_TIMEFRAMES, type NativeAnalysisTimeframe } from "../src/market/nativeCandles.ts";
import { mkdirSync, writeFileSync } from "node:fs";

const SIGNAL_PATTERNS = [
  /\bbuy now\b/i,
  /\bsell now\b/i,
  /\byou should (buy|sell)\b/i,
  /\benter (a |the )?trade\b/i,
  /\bguaranteed (profit|return|win)\b/i,
];

const MINUTES: Record<NativeAnalysisTimeframe, number> = {
  M5: 5,
  M15: 15,
  M30: 30,
  H1: 60,
  H4: 240,
  D1: 1440,
};

class FakeProvider implements AiProvider {
  readonly name = "fake";
  readonly model = "fake-model";
  calls = 0;
  lastMessages: readonly AiMessage[] | undefined;

  constructor(private readonly impl: AiProvider["chat"]) {}

  async chat(
    messages: readonly AiMessage[],
    _signal?: AbortSignal,
    _options?: AiChatOptions,
  ): Promise<{ text: string; inputTokens: number; outputTokens: number }> {
    this.calls += 1;
    this.lastMessages = messages;
    return await this.impl(messages, _signal, _options);
  }
}

const roots: string[] = [];
const databases: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) {
    rmSync(root, { recursive: true, force: true });
  }
  for (const path of databases.splice(0)) {
    rmSync(path, { force: true });
  }
});

function writeEurUsd(root: string): { asOfMs: number; lastClose: number } {
  const count = 220;
  const lastClose = 1.1 + (count - 1) * 0.001;
  const asOfMs = Date.UTC(2024, 0, 1) + count * 24 * 60 * 60_000;
  for (const timeframe of NATIVE_ANALYSIS_TIMEFRAMES) {
    const directory = join(root, timeframe);
    mkdirSync(directory, { recursive: true });
    const step = MINUTES[timeframe] * 60_000;
    const rows = Array.from({ length: count }, (_, index) => {
      const close = 1.1 + index * 0.001;
      const previous = index === 0 ? close : 1.1 + (index - 1) * 0.001;
      const time = new Date(Date.UTC(2024, 0, 1) + index * step);
      const pad = (value: number): string => String(value).padStart(2, "0");
      const stamp = `${time.getUTCFullYear()}-${pad(time.getUTCMonth() + 1)}-${pad(time.getUTCDate())} ${pad(time.getUTCHours())}:${pad(time.getUTCMinutes())}:${pad(time.getUTCSeconds())}`;
      const high = Math.max(previous, close) + 0.0002;
      const low = Math.min(previous, close) - 0.0002;
      return `${stamp},${previous.toFixed(5)},${high.toFixed(5)},${low.toFixed(5)},${close.toFixed(5)},1`;
    });
    writeFileSync(
      join(directory, "2024-01-01.csv"),
      ["time_utc,open,high,low,close,volume", ...rows].join("\n") + "\n",
    );
  }
  return { asOfMs, lastClose };
}

describe("analyze command", () => {
  it("keeps the no-argument educational reply", async () => {
    const parts = await buildAnalyzeReplies("  ");
    expect(parts).toEqual([renderAnalyzeReply()]);
    expect(parts[0]).toContain("Data: unavailable");
    expect(parts[0]).toMatch(/not connected/i);
  });

  it("reports EURUSD from the fixture and splits long output", async () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-analyze-"));
    roots.push(root);
    const { asOfMs, lastClose } = writeEurUsd(root);
    const parts = await buildAnalyzeReplies("eurusd extra", {
      root,
      asOfMs,
      cache: new Map(),
      splitLimit: 700,
    });
    expect(parts.length).toBeGreaterThan(1);
    for (const part of parts) {
      expect(part.length).toBeLessThanOrEqual(700);
      expect(part.length).toBeLessThanOrEqual(TELEGRAM_MESSAGE_LIMIT);
    }
    const text = parts.join("\n");
    expect(text).toContain("Tradexa technical report: EURUSD");
    expect(text).toContain("Analysis timestamp:");
    expect(text).toContain("UTC");
    expect(text).toContain("Trend: bullish");
    expect(text).toContain("EMA 20/50/200:");
    expect(text).toContain("RSI 14:");
    expect(text).toContain("MACD 12/26/9:");
    expect(text).toContain("ATR 14:");
    expect(text).toContain("Market structure:");
    expect(text).toContain("Support:");
    expect(text).toContain("Resistance:");
    expect(text).toMatch(/not a reversal signal/);
    expect(text).toMatch(/not a live quote/);
    expect(text).toContain(lastClose.toFixed(5));
    expect(text).not.toContain("9.99999");
    expect(text).not.toContain("Explanation:");
    for (const pattern of SIGNAL_PATTERNS) {
      expect(text).not.toMatch(pattern);
    }
  });

  it("does not invent prices for an unsupported symbol or a pair without native data", async () => {
    const unknown = await buildAnalyzeReplies("XYZ");
    expect(unknown.join("\n")).toMatch(/not a supported instrument/);
    expect(unknown.join("\n")).not.toMatch(/\b\d+\.\d{4,}\b/);

    const missing = await buildAnalyzeReplies("GBPUSD");
    expect(missing.join("\n")).toMatch(/no native historical candles/);
    expect(missing.join("\n")).toMatch(/EURUSD only/);
    expect(missing.join("\n")).not.toMatch(/\b\d+\.\d{4,}\b/);
  });

  it("asks the AI to explain the calculated report and still returns it when AI fails", async () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-analyze-"));
    roots.push(root);
    const { asOfMs, lastClose } = writeEurUsd(root);
    const provider = new FakeProvider(async (messages) => {
      expect(messages[0]?.content).toBe(ANALYSIS_EXPLANATION_PROMPT);
      const request = JSON.parse(messages[1]?.content ?? "{}") as {
        allowedCategories: string[];
        evidenceIds: string[];
      };
      expect(request.allowedCategories).toContain("aligned_bullish");
      expect(request.allowedCategories).not.toContain("aligned_bearish");
      expect(messages[1]?.content).not.toContain(lastClose.toFixed(5));
      return {
        text: JSON.stringify({
          evidenceIds: request.evidenceIds.slice(0, 1),
          category: "aligned_bullish",
        }),
        inputTokens: 20,
        outputTokens: 15,
      };
    });
    const explained = await buildAnalyzeReplies("EURUSD", {
      root,
      asOfMs,
      cache: new Map(),
      provider,
    });
    expect(provider.calls).toBe(1);
    const text = explained.join("\n");
    expect(text).toContain(lastClose.toFixed(5));
    expect(text).toContain("Explanation:");
    expect(text).toContain("agree and are bullish");
    expect(text).toContain("not a prediction");
    expect(text).not.toContain("9.99999");

    const failing = new FakeProvider(async () => {
      throw new Error("provider down");
    });
    const fallback = await buildAnalyzeReplies("EURUSD", {
      root,
      asOfMs,
      cache: new Map(),
      provider: failing,
    });
    const fallbackText = fallback.join("\n");
    expect(fallbackText).toContain(lastClose.toFixed(5));
    expect(fallbackText).toContain("Explanation unavailable");
  });

  it("does not call the provider when the token budget is exhausted", async () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-analyze-"));
    roots.push(root);
    const database = join(mkdtempSync(join(tmpdir(), "tradexa-db-")), "chat.sqlite");
    databases.push(database);
    const { asOfMs, lastClose } = writeEurUsd(root);
    const store: ChatStore = openChatStore(database);
    const budget = new TokenBudget(store, {
      dailyGlobalTokens: 1,
      dailyChatTokens: 1,
      maxOutputTokens: 1,
    });
    const provider = new FakeProvider(async () => ({
      text: "should not be called",
      inputTokens: 1,
      outputTokens: 1,
    }));
    const parts = await buildAnalyzeReplies("EURUSD", {
      root,
      asOfMs,
      cache: new Map(),
      provider,
      budget,
      chatId: 42,
      nowMs: Date.UTC(2026, 0, 2),
    });
    expect(provider.calls).toBe(0);
    const text = parts.join("\n");
    expect(text).toContain(lastClose.toFixed(5));
    expect(text).toMatch(/budget is exhausted/);
    store.close();
  });

  it("rejects unsupported AI claims and keeps the calculated report", async () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-analyze-ai-"));
    roots.push(root);
    const { asOfMs, lastClose } = writeEurUsd(root);
    const cases = [
      {
        name: "fabricated price",
        text: "EURUSD is 9.99999 and bullish.",
        rejected: true,
      },
      {
        name: "wrong EMA",
        text: JSON.stringify({
          evidenceIds: ["trend-D1"],
          category: "aligned_bullish",
          ema20: 9.5,
        }),
        rejected: true,
      },
      {
        name: "bullish category",
        text: JSON.stringify({ evidenceIds: ["trend-D1"], category: "aligned_bullish" }),
        rejected: false,
      },
      {
        name: "fabricated support",
        text: JSON.stringify({
          evidenceIds: ["support-1.50000"],
          category: "aligned_bullish",
        }),
        rejected: true,
      },
      {
        name: "invalid JSON",
        text: "not-json",
        rejected: true,
      },
      {
        name: "empty",
        text: "   ",
        rejected: true,
      },
    ];
    for (const item of cases) {
      const parts = await buildAnalyzeReplies("EURUSD", {
        root,
        asOfMs,
        cache: new Map(),
        provider: new FakeProvider(async () => ({
          text: item.text,
          inputTokens: 1,
          outputTokens: 1,
        })),
      });
      const text = parts.join("\n");
      expect(text, item.name).toContain(lastClose.toFixed(5));
      expect(text, item.name).not.toContain("9.99999");
      expect(text, item.name).not.toContain("9.5");
      expect(text, item.name).not.toContain("1.50000");
      if (item.rejected) {
        expect(text, item.name).toMatch(/calculated report is unchanged/);
        expect(text, item.name).not.toContain("agree and are bullish");
      } else {
        expect(text, item.name).toContain("agree and are bullish");
      }
    }

    const falling = mkdtempSync(join(tmpdir(), "tradexa-analyze-bear-"));
    roots.push(falling);
    const bearish = writeDirection(falling, -0.001);
    const contradicted = await buildAnalyzeReplies("EURUSD", {
      root: falling,
      asOfMs: bearish.asOfMs,
      cache: new Map(),
      provider: new FakeProvider(async () => ({
        text: JSON.stringify({ evidenceIds: ["trend-D1"], category: "aligned_bullish" }),
        inputTokens: 1,
        outputTokens: 1,
      })),
    });
    const bearishText = contradicted.join("\n");
    expect(bearishText).toContain("bearish");
    expect(bearishText).toMatch(/did not match the calculated evidence/);
    expect(bearishText).not.toContain("agree and are bullish");
  });
});

function writeDirection(root: string, stepPrice: number): { asOfMs: number; lastClose: number } {
  const count = 220;
  const lastClose = 1.5 + (count - 1) * stepPrice;
  const asOfMs = Date.UTC(2024, 0, 1) + count * 24 * 60 * 60_000;
  for (const timeframe of NATIVE_ANALYSIS_TIMEFRAMES) {
    const directory = join(root, timeframe);
    mkdirSync(directory, { recursive: true });
    const step = MINUTES[timeframe] * 60_000;
    const rows = Array.from({ length: count }, (_, index) => {
      const close = 1.5 + index * stepPrice;
      const previous = index === 0 ? close : 1.5 + (index - 1) * stepPrice;
      const time = new Date(Date.UTC(2024, 0, 1) + index * step);
      const pad = (value: number): string => String(value).padStart(2, "0");
      const stamp = `${time.getUTCFullYear()}-${pad(time.getUTCMonth() + 1)}-${pad(time.getUTCDate())} ${pad(time.getUTCHours())}:${pad(time.getUTCMinutes())}:${pad(time.getUTCSeconds())}`;
      return `${stamp},${previous.toFixed(5)},${Math.max(previous, close).toFixed(5)},${Math.min(previous, close).toFixed(5)},${close.toFixed(5)},1`;
    });
    writeFileSync(
      join(directory, "2024-01-01.csv"),
      ["time_utc,open,high,low,close,volume", ...rows].join("\n") + "\n",
    );
  }
  return { asOfMs, lastClose };
}
