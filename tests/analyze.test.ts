import { describe, expect, it } from "vitest";
import { createUnavailableReport, formatAnalysisReport } from "../src/analysis/report.ts";
import { renderAnalyzeReply } from "../src/commands/analyze.ts";

const SIGNAL_PATTERNS = [
  /\bbuy now\b/i,
  /\bsell now\b/i,
  /\byou should (buy|sell)\b/i,
  /\benter (a |the )?trade\b/i,
  /\btake profit\b/i,
  /\bstop[- ]loss\b/i,
  /\bguaranteed (profit|return|win)\b/i,
];

const PRICE_LEVEL_PATTERN = /\b\d+\.\d{2,}\b/;

describe("createUnavailableReport", () => {
  it("always marks data as unavailable with empty levels", () => {
    const report = createUnavailableReport();
    expect(report.dataStatus).toBe("unavailable");
    expect(report.trend).toBeNull();
    expect(report.support).toEqual([]);
    expect(report.resistance).toEqual([]);
    expect(report.notes.length).toBeGreaterThan(0);
    expect(report.disclaimer.length).toBeGreaterThan(0);
    expect(report).not.toHaveProperty("pair");
    expect(report).not.toHaveProperty("timeframe");
  });
});

describe("renderAnalyzeReply", () => {
  it("explains that live market data is not connected", () => {
    const reply = renderAnalyzeReply();
    expect(reply).toBe(formatAnalysisReport(createUnavailableReport()));
    expect(reply).toContain("Data: unavailable");
    expect(reply).toMatch(/not connected/i);
    expect(reply).toContain("Trend: not available (no live data)");
    expect(reply).toContain("Support: not available (no live data)");
    expect(reply).toContain("Resistance: not available (no live data)");
  });

  it("walks through timeframe, trend, support/resistance, and price action", () => {
    const reply = renderAnalyzeReply();
    expect(reply).toMatch(/timeframe/i);
    expect(reply).toMatch(/Higher Highs/);
    expect(reply).toMatch(/Higher Lows/);
    expect(reply).toMatch(/Lower Highs/);
    expect(reply).toMatch(/Lower Lows/);
    expect(reply).toMatch(/\brange\b/i);
    expect(reply).toMatch(/support/i);
    expect(reply).toMatch(/resistance/i);
    expect(reply).toMatch(/zones, not exact prices/i);
    expect(reply).toMatch(/price action/i);
    expect(reply).toMatch(/swings/i);
    expect(reply).toMatch(/pauses/i);
    expect(reply).toMatch(/not a trading signal/i);
  });

  it("does not invent prices, levels, or trading signals", () => {
    const reply = renderAnalyzeReply();
    expect(reply).not.toMatch(PRICE_LEVEL_PATTERN);
    for (const pattern of SIGNAL_PATTERNS) {
      expect(reply, String(pattern)).not.toMatch(pattern);
    }
    expect(reply).toContain("does not generate trading signals");
  });
});
