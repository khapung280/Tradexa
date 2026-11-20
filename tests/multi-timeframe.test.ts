import { mkdirSync, writeFileSync } from "node:fs";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { formatInstrumentAnalysis } from "../src/analysis/formatTechnical.ts";
import { analyzeInstrument } from "../src/analysis/technical.ts";
import {
  NATIVE_ANALYSIS_TIMEFRAMES,
  type NativeAnalysisTimeframe,
} from "../src/market/nativeCandles.ts";

const MINUTES: Record<NativeAnalysisTimeframe, number> = {
  M5: 5,
  M15: 15,
  M30: 30,
  H1: 60,
  H4: 240,
  D1: 1440,
};

const roots: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) {
    rmSync(root, { recursive: true, force: true });
  }
});

function csvTime(timeMs: number): string {
  const date = new Date(timeMs);
  const pad = (value: number): string => String(value).padStart(2, "0");
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}`;
}

function writeSeries(
  root: string,
  timeframe: NativeAnalysisTimeframe,
  closes: readonly number[],
  extra?: { close: number; timeMs: number },
): void {
  const directory = join(root, timeframe);
  mkdirSync(directory, { recursive: true });
  const step = MINUTES[timeframe] * 60_000;
  const start = Date.UTC(2024, 0, 1);
  const rows = closes.map((close, index) => {
    const previous = index === 0 ? close : closes[index - 1] ?? close;
    const high = Math.max(previous, close) + 0.0002;
    const low = Math.min(previous, close) - 0.0002;
    return `${csvTime(start + index * step)},${previous.toFixed(5)},${high.toFixed(5)},${low.toFixed(5)},${close.toFixed(5)},1`;
  });
  if (extra !== undefined) {
    const previous = closes[closes.length - 1] ?? extra.close;
    const high = Math.max(previous, extra.close) + 0.0002;
    const low = Math.min(previous, extra.close) - 0.0002;
    rows.push(
      `${csvTime(extra.timeMs)},${previous.toFixed(5)},${high.toFixed(5)},${low.toFixed(5)},${extra.close.toFixed(5)},1`,
    );
  }
  writeFileSync(join(directory, "2024-01-01.csv"), ["time_utc,open,high,low,close,volume", ...rows].join("\n") + "\n");
}

function rising(count: number): number[] {
  return Array.from({ length: count }, (_, index) => 1.1 + index * 0.001);
}

function falling(count: number): number[] {
  return Array.from({ length: count }, (_, index) => 1.5 - index * 0.0005);
}

describe("multi-timeframe analysis", () => {
  it("reports all six native timeframes from closed candles only", () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-mtf-"));
    roots.push(root);
    for (const timeframe of NATIVE_ANALYSIS_TIMEFRAMES) {
      writeSeries(
        root,
        timeframe,
        rising(220),
        timeframe === "M5"
          ? { close: 7.77777, timeMs: Date.UTC(2026, 0, 1) }
          : undefined,
      );
    }
    const asOfMs = Date.UTC(2026, 0, 1, 0, 3, 0);
    const analysis = analyzeInstrument("EURUSD", { root, asOfMs, cache: new Map() });
    expect(analysis.kind).toBe("report");
    if (analysis.kind !== "report") {
      return;
    }
    expect(analysis.sections.map((section) => section.timeframe)).toEqual([
      "M5",
      "M15",
      "M30",
      "H1",
      "H4",
      "D1",
    ]);
    for (const section of analysis.sections) {
      expect(section.status).toBe("ok");
      if (section.status !== "ok") {
        continue;
      }
      expect(section.candleCloseMs).toBeLessThanOrEqual(asOfMs);
      expect(section.close).not.toBeCloseTo(7.77777, 4);
      expect(section.indicators.ema20).not.toBeNull();
      expect(section.indicators.ema50).not.toBeNull();
      expect(section.indicators.ema200).not.toBeNull();
      expect(section.indicators.rsi14).not.toBeNull();
      expect(section.indicators.macd).not.toBeNull();
      expect(section.indicators.atr14).not.toBeNull();
      expect(section.trend.classification).toBe("bullish");
    }
    const text = formatInstrumentAnalysis(analysis);
    expect(text).toMatch(/agree: bullish/);
    expect(text).not.toContain("7.77777");
    expect(analysis.summary.join(" ")).not.toMatch(/Timeframes conflict/);
  });

  it("describes a higher-timeframe direction against a lower-timeframe move", () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-mtf-"));
    roots.push(root);
    for (const timeframe of ["D1", "H4", "H1"] as const) {
      writeSeries(root, timeframe, rising(220));
    }
    for (const timeframe of ["M30", "M15", "M5"] as const) {
      writeSeries(root, timeframe, falling(220));
    }
    const analysis = analyzeInstrument("EURUSD", {
      root,
      asOfMs: Date.UTC(2026, 0, 1),
      cache: new Map(),
    });
    expect(analysis.kind).toBe("report");
    if (analysis.kind !== "report") {
      return;
    }
    const byTimeframe = new Map(analysis.sections.map((section) => [section.timeframe, section]));
    expect(byTimeframe.get("D1")).toMatchObject({ status: "ok", trend: { classification: "bullish" } });
    expect(byTimeframe.get("M5")).toMatchObject({ status: "ok", trend: { classification: "bearish" } });
    const summary = analysis.summary.join(" ");
    expect(summary).toMatch(/conflict: bullish D1, H4, H1; bearish M30, M15, M5/);
    expect(summary).toMatch(/lower-timeframe move against the higher-timeframe direction/);
  });

  it("keeps a missing timeframe unavailable and does not borrow another timeframe's candles", () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-mtf-"));
    roots.push(root);
    for (const timeframe of NATIVE_ANALYSIS_TIMEFRAMES) {
      if (timeframe === "H4") {
        continue;
      }
      writeSeries(root, timeframe, rising(30));
    }
    const analysis = analyzeInstrument("EURUSD", {
      root,
      asOfMs: Date.UTC(2026, 0, 1),
      cache: new Map(),
    });
    expect(analysis.kind).toBe("report");
    if (analysis.kind !== "report") {
      return;
    }
    const missing = analysis.sections.find((section) => section.timeframe === "H4");
    expect(missing).toMatchObject({ status: "unavailable" });
    const m5 = analysis.sections.find((section) => section.timeframe === "M5");
    expect(m5).toMatchObject({ status: "ok", trend: { classification: "insufficient_data" } });
    expect(formatInstrumentAnalysis(analysis)).toMatch(/H4/);
    expect(formatInstrumentAnalysis(analysis)).toMatch(/not available/);
  });
});
