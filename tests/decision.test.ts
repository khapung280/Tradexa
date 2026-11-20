import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { AiProvider } from "../src/ai/types.ts";
import type { InstrumentAnalysis, TimeframeSection } from "../src/analysis/technical.ts";
import { TokenBudget } from "../src/budget/ledger.ts";
import { buildDecisionReplies } from "../src/commands/decision.ts";
import { renderHelpReply } from "../src/commands/help.ts";
import { renderStartReply } from "../src/commands/start.ts";
import {
  ASSESSMENT_OUTPUT_TOKEN_LIMIT,
  ASSESSMENT_REASONING_EFFORT,
  ASSESSMENT_RESPONSE_FORMAT,
  parseAssessment,
} from "../src/decision/assess.ts";
import { buildEvidence, buildSnapshot } from "../src/decision/evidence.ts";
import { classifyFreshness } from "../src/decision/freshness.ts";
import { runDecision } from "../src/decision/run.ts";
import { LIVE_WAIT_LABEL, RESEARCH_LABEL } from "../src/decision/record.ts";
import { openChatStore, type ChatStore } from "../src/db/store.ts";
import { recordLoss } from "../src/risk/kill.ts";
import type { NativeAnalysisTimeframe } from "../src/market/nativeCandles.ts";

const NOW = Date.UTC(2026, 10, 20, 12, 0, 0);
const CLOSE = NOW - 60 * 60_000;

function section(timeframe: NativeAnalysisTimeframe, direction: "bullish" | "bearish", warnings: string[] = []): TimeframeSection {
  const bullish = direction === "bullish";
  return {
    status: "ok",
    timeframe,
    candleOpenMs: CLOSE - 60 * 60_000,
    candleCloseMs: CLOSE,
    close: 1.1,
    candlesUsed: 250,
    filesRead: 2,
    indicators: {
      ema20: bullish ? 3 : 1,
      ema50: 2,
      ema200: bullish ? 1 : 3,
      emaCrossover: "none",
      rsi14: 50,
      macd: bullish ? 1 : -1,
      macdSignal: 0,
      macdHistogram: bullish ? 1 : -1,
      macdCrossover: "none",
      atr14: 0.001,
      volatility: "typical",
    },
    trend: {
      classification: direction,
      evidence: [`${timeframe} is ${direction}.`],
      facts: { ema20: 1, ema50: 1, ema200: 1, close: 1.1, rsi: 50, atr: 0.001, slope: bullish ? 1 : -1 },
    },
    structure: { state: "insufficient_data", swings: [], lastHighLabel: null, lastLowLabel: null, note: "none" },
    zones: {
      support: [],
      resistance: [],
      historicalSwingLows: [],
      historicalSwingHighs: [],
      omitted: { support: 0, resistance: 0, historicalSwingLows: 0, historicalSwingHighs: 0 },
      tolerance: null,
      emptySupportReason: "none",
      emptyResistanceReason: "none",
      note: "none",
    },
    warnings,
  };
}

function report(sections: TimeframeSection[]): InstrumentAnalysis {
  return { kind: "report", symbol: "EURUSD", asOfMs: NOW, sections, summary: [] };
}

function analyzeFrom(sections: TimeframeSection[]) {
  return () => report(sections);
}

function provider(text: string, name = "fixture"): AiProvider {
  return {
    name,
    model: "fixture-model",
    chat: async () => ({ text, inputTokens: 1, outputTokens: 1 }),
  };
}

function validAssessment(classification: "BUY" | "SELL" | "WAIT", evidenceIds: string[] = []): string {
  return JSON.stringify({
    schemaVersion: 1,
    marketBias: classification === "SELL" ? "BEARISH" : classification === "BUY" ? "BULLISH" : "NEUTRAL",
    evidenceIds,
    supportingReasons: ["The supplied ids were used."],
    contradictingReasons: [],
    missingEvidence: [],
    riskObservations: ["No live quote is available."],
    assessmentSummary: "The assessment uses only the supplied evidence.",
    recommendedClassification: classification,
    limitations: ["This is not a win probability."],
  });
}

describe("decision safety", () => {
  const dirs: string[] = [];
  afterEach(() => {
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  function store(): ChatStore {
    const dir = mkdtempSync(join(tmpdir(), "tradexa-decision-"));
    dirs.push(dir);
    return openChatStore(join(dir, "tradexa.db"));
  }

  it("keeps historical candles non-actionable and preserves a conflict", async () => {
    const aligned = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
      provider: provider(validAssessment("BUY", ["TREND_H1_BULLISH"])),
    });
    expect(aligned.actionable).toBe(false);
    expect(aligned.finalClassification).toBe("WAIT");
    expect(aligned.finalLabel).toBe(LIVE_WAIT_LABEL);
    expect(aligned.research.label).toBe(RESEARCH_LABEL);
    expect(aligned.research.applicability).toBe("NON_ACTIONABLE");
    expect(aligned.research.classification).toBe("BUY");
    expect(aligned.levels.entry).toBe("NOT AVAILABLE");
    expect(aligned.snapshot.liveSourceVerified).toBe(false);
    expect(aligned.snapshot.spread.available).toBe(false);
    expect(aligned.strategyEvidence.available).toBe(true);
    if (aligned.strategyEvidence.available) {
      expect(aligned.strategyEvidence.disclaimer).toContain("not future performance");
    }

    const conflict = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bearish")]),
    });
    expect(conflict.research.classification).toBe("WAIT");
    expect(conflict.research.reason).toContain("bullish and bearish");
    expect(conflict.evidence.some((item) => item.id === "MTF_CONFLICT_BULLISH_BEARISH")).toBe(true);
    expect(conflict.actionable).toBe(false);
  });

  it("does not let an AI recommendation override a failed direction", async () => {
    const result = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bearish")]),
      provider: provider(validAssessment("BUY", ["TREND_H1_BULLISH"])),
    });
    expect(result.research.classification).toBe("WAIT");
    expect(result.research.reason).toContain("was not used");
    expect(result.finalClassification).toBe("WAIT");
  });

  it("downgrades a research direction when the AI disagrees", async () => {
    const result = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
      provider: provider(validAssessment("SELL", ["TREND_H1_BULLISH"])),
    });
    expect(result.ai.status).toBe("accepted");
    expect(result.research.classification).toBe("WAIT");
    expect(result.research.reason).toContain("did not confirm");
  });

  it("rejects invalid, empty, unknown, and price-bearing AI responses", async () => {
    const allowed = new Set(["TREND_H1_BULLISH"]);
    expect(parseAssessment("", allowed).ok).toBe(false);
    expect(parseAssessment("{", allowed).ok).toBe(false);
    expect(parseAssessment("```json\n{\"schemaVersion\":1}\n```", allowed).reason).toBe("AI response was not valid JSON.");
    expect(parseAssessment("Assessment follows {\"schemaVersion\":1}", allowed).reason).toBe("AI response was not valid JSON.");
    expect(parseAssessment("[]", allowed).reason).toBe("AI response was not a JSON object.");
    const omitted = { schemaVersion: 1, marketBias: "NEUTRAL", evidenceIds: [], recommendedClassification: "WAIT", symbol: "EURUSD" };
    expect(parseAssessment(JSON.stringify(omitted), allowed).reason).toBe("AI assessment field supportingReasons was missing.");
    expect(parseAssessment(validAssessment("MAYBE" as "WAIT"), allowed).reason).toBe("AI classification was not BUY, SELL, or WAIT.");
    expect(parseAssessment(validAssessment("BUY").replace("BULLISH", "MAYBE"), allowed).ok).toBe(false);
    expect(parseAssessment(validAssessment("BUY", ["NOT_A_REAL_ID"]), allowed).ok).toBe(false);
    const priced = JSON.parse(validAssessment("BUY")) as { entry: number };
    priced.entry = 1.1;
    expect(parseAssessment(JSON.stringify(priced), allowed).ok).toBe(false);
    const claimed = JSON.parse(validAssessment("WAIT")) as { assessmentSummary: string };
    claimed.assessmentSummary = "The entry price is 1.1000.";
    expect(parseAssessment(JSON.stringify(claimed), allowed).ok).toBe(false);

    const rejected = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
      provider: provider("not json"),
    });
    expect(rejected.ai.status).toBe("rejected");
    expect(rejected.finalClassification).toBe("WAIT");
    expect(rejected.research.classification).toBe("BUY");

    let requestedSchema = false;
    const accepted = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
      provider: {
        name: "fixture",
        model: "fixture-model",
        chat: async (_messages, _signal, options) => {
          requestedSchema = options?.responseFormat === ASSESSMENT_RESPONSE_FORMAT
            && options.reasoningEffort === ASSESSMENT_REASONING_EFFORT
            && options.maxOutputTokens === ASSESSMENT_OUTPUT_TOKEN_LIMIT;
          return { text: validAssessment("WAIT", ["TREND_H1_BULLISH"]), inputTokens: 1, outputTokens: 1 };
        },
      },
    });
    expect(requestedSchema).toBe(true);
    expect(accepted.ai.status).toBe("accepted");
    expect(accepted.actionable).toBe(false);
    expect(accepted.finalClassification).toBe("WAIT");

    const providerJson = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
      provider: {
        name: "fixture",
        model: "fixture-model",
        chat: async () => {
          throw new Error("AI response was not valid JSON.");
        },
      },
    });
    expect(providerJson.ai.status).toBe("unavailable");
    expect(providerJson.actionable).toBe(false);
  });

  it("fails closed when the provider times out, is rate limited, or errors", async () => {
    const cases = [
      Object.assign(new Error("timed out"), { name: "TimeoutError" }),
      new Error("AI request failed with HTTP 429."),
      new Error("AI request failed with HTTP 500."),
      new Error("AI request failed before a response was received."),
    ];
    for (const error of cases) {
      const result = await runDecision({
        symbol: "EURUSD",
        nowMs: NOW,
        analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
        provider: { name: "fixture", model: "fixture-model", chat: async () => { throw error; } },
      });
      expect(result.ai.status).toBe("unavailable");
      expect(result.actionable).toBe(false);
      expect(result.finalLabel).toBe(LIVE_WAIT_LABEL);
    }
  });

  it("returns WAIT for a missing file, a bad read, a short history, and the wrong symbol", async () => {
    const missing = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: () => ({ kind: "unavailable", symbol: "EURUSD", text: "Native candle files are missing." }),
    });
    expect(missing.finalClassification).toBe("WAIT");
    expect(missing.research.classification).toBe("WAIT");
    expect(missing.research.reason).toContain("200 closed candles");

    const thrown = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: () => { throw new Error("Invalid OHLC"); },
    });
    expect(thrown.finalClassification).toBe("WAIT");
    expect(thrown.evidence.some((item) => item.id === "DATA_UNAVAILABLE")).toBe(true);

    const short = section("H1", "bullish");
    if (short.status === "ok") {
      short.candlesUsed = 10;
    }
    const insufficient = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([short, section("H4", "bullish")]),
    });
    expect(insufficient.research.classification).toBe("WAIT");
    expect(insufficient.snapshot.timeframes.find((item) => item.timeframe === "H1")?.completeness).toBe("insufficient");

    const wrong = await runDecision({ symbol: "NOTREAL", nowMs: NOW, analyze: () => ({ kind: "unsupported", symbol: "NOTREAL", text: "unsupported" }) });
    expect(wrong.finalClassification).toBe("WAIT");
    expect(wrong.gates.find((gate) => gate.id === "symbol")?.passed).toBe(false);
  });

  it("marks an unclassified gap and keeps a requested live mode non-actionable", async () => {
    const gapped = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      analyze: analyzeFrom([
        section("H1", "bullish", ["Calculations restarted after an unclassified gap."]),
        section("H4", "bullish"),
      ]),
    });
    expect(gapped.snapshot.timeframes.find((item) => item.timeframe === "H1")?.gapQuality).toBe("unclassified_gap");
    expect(gapped.gates.find((gate) => gate.id === "gaps")?.passed).toBe(false);
    expect(gapped.actionable).toBe(false);

    const live = await runDecision({
      symbol: "EURUSD",
      mode: "live",
      nowMs: NOW,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
    });
    expect(live.snapshot.mode).toBe("LIVE");
    expect(live.snapshot.timeframes.every((item) => item.freshness === "stale")).toBe(true);
    expect(live.finalLabel).toBe(LIVE_WAIT_LABEL);
    expect(live.actionable).toBe(false);
    expect(classifyFreshness("LIVE", 1_000, { liveMaxAgeMs: 60_000 }, false)).toBe("stale");
    expect(classifyFreshness("LIVE", 1_000, { liveMaxAgeMs: 60_000 }, true)).toBe("fresh");
    expect(classifyFreshness("LIVE", DEFAULT_AGE, { liveMaxAgeMs: 1_000 }, true)).toBe("stale");
    expect(classifyFreshness("HISTORICAL", DEFAULT_AGE)).toBe("historical");
  });

  it("blocks research when the user-reported kill switch is on and stores a stable fingerprint", async () => {
    const saved = store();
    saved.saveMessage(7, "user", "existing");
    recordLoss(saved, { chatId: 7, amount: 100, currency: "USD", messageId: 1 }, 100, NOW);
    const first = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      chatId: 7,
      store: saved,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
    });
    const second = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      chatId: 7,
      store: saved,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
    });
    expect(first.research.classification).toBe("WAIT");
    expect(first.research.reason).toContain("kill switch");
    expect(first.risk.status).toBe("blocked");
    expect(first.fingerprint).toBe(second.fingerprint);
    expect(first.id).not.toBe(second.id);
    expect(saved.getDecision(first.id)?.fingerprint).toBe(first.fingerprint);
    expect(saved.getHistory(7).map((message) => message.content)).toContain("existing");
    expect(() => saved.insertDecision({ ...first, actionable: true })).toThrow(/actionable/i);
    saved.close();
  });

  it("does not call the provider when the token budget is exhausted", async () => {
    const saved = store();
    let calls = 0;
    const result = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      chatId: 3,
      store: saved,
      budget: new TokenBudget(saved, { dailyGlobalTokens: 0, dailyChatTokens: 0, maxOutputTokens: 100 }, () => NOW),
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
      provider: { name: "fixture", model: "fixture-model", chat: async () => { calls += 1; return { text: validAssessment("BUY"), inputTokens: 1, outputTokens: 1 }; } },
    });
    expect(calls).toBe(0);
    expect(result.ai.status).toBe("unavailable");
    expect(result.ai.reason).toContain("budget");
    expect(result.finalClassification).toBe("WAIT");
    saved.close();
  });

  it("answers /decision without a live signal and keeps the existing command text", async () => {
    const replies = await buildDecisionReplies("EURUSD", {
      nowMs: NOW,
      chatId: 1,
      provider: null,
      analyze: analyzeFrom([section("H1", "bullish"), section("H4", "bullish")]),
    });
    const text = replies.join("\n");
    expect(text).toContain(LIVE_WAIT_LABEL);
    expect(text).toContain(RESEARCH_LABEL);
    expect(text).not.toContain("API_KEY");
    expect(renderHelpReply()).toContain("/decision EURUSD");
    expect(renderHelpReply()).toContain("/timeframe — Supported chart timeframes (M1–D1)");
    expect(renderStartReply()).toContain("/decision EURUSD");
    expect(renderStartReply()).toContain("/session");

    const snapshot = buildSnapshot(report([section("H1", "bullish"), section("H4", "bullish")]), NOW, "HISTORICAL", { liveMaxAgeMs: 1 });
    const evidence = buildEvidence(report([section("H1", "bullish"), section("H4", "bullish")]));
    expect(snapshot.mode).toBe("HISTORICAL");
    expect(evidence.some((item) => item.id === "EMA_H1_20_ABOVE_50")).toBe(true);
    expect(evidence.some((item) => item.id === "SPREAD_UNAVAILABLE")).toBe(true);
  });
});

const DEFAULT_AGE = 10 * 24 * 60 * 60_000;
