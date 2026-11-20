import { describe, expect, it } from "vitest";
import { ASSESSMENT_RESPONSE_FORMAT, parseAssessment } from "../src/decision/assess.ts";
import { runDecision } from "../src/decision/run.ts";
import type { InstrumentAnalysis, TimeframeSection } from "../src/analysis/technical.ts";
import type { NativeAnalysisTimeframe } from "../src/market/nativeCandles.ts";

const REQUIRED = [
  "schemaVersion",
  "marketBias",
  "evidenceIds",
  "supportingReasons",
  "contradictingReasons",
  "missingEvidence",
  "riskObservations",
  "assessmentSummary",
  "recommendedClassification",
  "limitations",
] as const;

const LISTS = [
  "evidenceIds",
  "supportingReasons",
  "contradictingReasons",
  "missingEvidence",
  "riskObservations",
  "limitations",
] as const;

function assessment(patch: Record<string, unknown> = {}): string {
  return JSON.stringify({
    schemaVersion: 1,
    marketBias: "NEUTRAL",
    evidenceIds: [],
    supportingReasons: [],
    contradictingReasons: [],
    missingEvidence: [],
    riskObservations: [],
    assessmentSummary: "The assessment uses only the supplied evidence.",
    recommendedClassification: "WAIT",
    limitations: [],
    ...patch,
  });
}

describe("assessment field validation", () => {
  const allowed = new Set(["E1"]);

  it("accepts the ten required fields at their maximum sizes", () => {
    const item = "a".repeat(240);
    const parsed = parseAssessment(assessment({
      evidenceIds: Array.from({ length: 12 }, () => "E1"),
      supportingReasons: Array.from({ length: 8 }, () => item),
      assessmentSummary: "b".repeat(500),
    }), allowed);
    expect(parsed.ok).toBe(true);
  });

  it("names each missing or unexpected field without including its value", () => {
    for (const field of REQUIRED) {
      const body = JSON.parse(assessment()) as Record<string, unknown>;
      delete body[field];
      const parsed = parseAssessment(JSON.stringify(body), allowed);
      expect(parsed.ok).toBe(false);
      if (!parsed.ok) {
        expect(parsed.reason).toBe(`AI assessment field ${field} was missing.`);
      }
    }
    const extra = parseAssessment(assessment({ symbol: "EURUSD" }), allowed);
    expect(extra.ok).toBe(false);
    if (!extra.ok) {
      expect(extra.reason).toBe("AI assessment field symbol was unexpected.");
      expect(extra.reason).not.toContain("EURUSD");
    }
  });

  it("rejects an empty summary, an oversized summary, and a non-string summary by category", () => {
    expect(parseAssessment(assessment({ assessmentSummary: "" }), allowed).reason)
      .toBe("AI assessment field assessmentSummary was empty.");
    const over = parseAssessment(assessment({ assessmentSummary: "c".repeat(501) }), allowed);
    expect(over.ok).toBe(false);
    if (!over.ok) {
      expect(over.reason).toBe("AI assessment field assessmentSummary was 501 characters, maximum 500.");
      expect(over.reason).not.toContain("ccc");
    }
    expect(parseAssessment(assessment({ assessmentSummary: 1 }), allowed).reason)
      .toBe("AI assessment field assessmentSummary was not a string.");
  });

  it("rejects list type, count, empty item, and item length by category", () => {
    expect(parseAssessment(assessment({ supportingReasons: "text" }), allowed).reason)
      .toBe("AI assessment field supportingReasons was not an array.");
    expect(parseAssessment(assessment({ supportingReasons: Array.from({ length: 9 }, () => "ok") }), allowed).reason)
      .toBe("AI assessment field supportingReasons had 9 items, maximum 8.");
    expect(parseAssessment(assessment({ evidenceIds: Array.from({ length: 13 }, () => "E1") }), allowed).reason)
      .toBe("AI assessment field evidenceIds had 13 items, maximum 12.");
    expect(parseAssessment(assessment({ limitations: [""] }), allowed).reason)
      .toBe("AI assessment field limitations item 0 was empty.");
    const longItem = "d".repeat(241);
    const long = parseAssessment(assessment({ riskObservations: [longItem] }), allowed);
    expect(long.ok).toBe(false);
    if (!long.ok) {
      expect(long.reason).toBe("AI assessment field riskObservations item 0 was 241 characters, maximum 240.");
      expect(long.reason).not.toContain(longItem);
    }
    expect(parseAssessment(assessment({ missingEvidence: [1] }), allowed).reason)
      .toBe("AI assessment field missingEvidence item 0 was not a string.");
  });

  it("rejects invalid enums, malformed JSON, fences, and surrounding prose", () => {
    expect(parseAssessment(assessment({ marketBias: "MAYBE" }), allowed).reason)
      .toBe("AI market bias was not an allowed value.");
    expect(parseAssessment(assessment({ recommendedClassification: "HOLD" }), allowed).reason)
      .toBe("AI classification was not BUY, SELL, or WAIT.");
    expect(parseAssessment("{", allowed).reason).toBe("AI response was not valid JSON.");
    expect(parseAssessment("```json\n{\"schemaVersion\":1}\n```", allowed).reason)
      .toBe("AI response was not valid JSON.");
    expect(parseAssessment(`Note ${assessment()}`, allowed).reason)
      .toBe("AI response was not valid JSON.");
  });

  it("sends supported item-count limits and does not send ignored string-length keywords", () => {
    const properties = ASSESSMENT_RESPONSE_FORMAT.json_schema.schema.properties;
    expect(properties.evidenceIds).toMatchObject({ maxItems: 12 });
    for (const field of LISTS) {
      if (field === "evidenceIds") continue;
      expect(properties[field]).toMatchObject({ maxItems: 8 });
    }
    expect(JSON.stringify(ASSESSMENT_RESPONSE_FORMAT)).not.toContain("maxLength");
    expect(JSON.stringify(ASSESSMENT_RESPONSE_FORMAT)).not.toContain("minLength");
  });

  it("does not store an oversized assessment as an accepted decision", async () => {
    const now = Date.UTC(2026, 10, 20, 12, 0, 0);
    const close = now - 60 * 60_000;
    const section = (timeframe: NativeAnalysisTimeframe): TimeframeSection => ({
      status: "ok",
      timeframe,
      candleOpenMs: close - 60 * 60_000,
      candleCloseMs: close,
      close: 1.1,
      candlesUsed: 250,
      filesRead: 2,
      indicators: {
        ema20: 3, ema50: 2, ema200: 1, emaCrossover: "none", rsi14: 50,
        macd: 1, macdSignal: 0, macdHistogram: 1, macdCrossover: "none", atr14: 0.001, volatility: "typical",
      },
      trend: { classification: "bullish", evidence: ["aligned"], facts: { ema20: 1, ema50: 1, ema200: 1, close: 1.1, rsi: 50, atr: 0.001, slope: 1 } },
      structure: { state: "insufficient_data", swings: [], lastHighLabel: null, lastLowLabel: null, note: "none" },
      zones: {
        support: [], resistance: [], historicalSwingLows: [], historicalSwingHighs: [],
        omitted: { support: 0, resistance: 0, historicalSwingLows: 0, historicalSwingHighs: 0 },
        tolerance: null, emptySupportReason: "none", emptyResistanceReason: "none", note: "none",
      },
      warnings: [],
    });
    const report: InstrumentAnalysis = {
      kind: "report", symbol: "EURUSD", asOfMs: now, sections: [section("H1"), section("H4")], summary: [],
    };
    const result = await runDecision({
      symbol: "EURUSD",
      nowMs: now,
      analyze: () => report,
      provider: {
        name: "fixture",
        model: "fixture-model",
        chat: async () => ({ text: assessment({ assessmentSummary: "e".repeat(501) }), inputTokens: 1, outputTokens: 1 }),
      },
    });
    expect(result.ai.status).toBe("rejected");
    expect(result.actionable).toBe(false);
    expect(result.finalClassification).toBe("WAIT");
    if (result.ai.status === "rejected") {
      expect(result.ai.reason).toContain("assessmentSummary");
      expect(result.ai.reason).not.toContain("eee");
    }
  });
});
