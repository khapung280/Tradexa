import { mkdirSync, writeFileSync } from "node:fs";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { formatInstrumentAnalysis } from "../src/analysis/formatTechnical.ts";
import { calculationStartIndex, classifyCandleGap } from "../src/analysis/gaps.ts";
import { analyzeInstrument } from "../src/analysis/technical.ts";
import { readClosedNativeCandles } from "../src/market/nativeCandles.ts";

const roots: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) {
    rmSync(root, { recursive: true, force: true });
  }
});

function root(): string {
  const path = mkdtempSync(join(tmpdir(), "tradexa-gaps-"));
  roots.push(path);
  return path;
}

function stamp(timeMs: number): string {
  const date = new Date(timeMs);
  const pad = (value: number): string => String(value).padStart(2, "0");
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())}`;
}

function writeBars(
  dataset: string,
  timeframe: "M5" | "H1",
  bars: readonly { timeMs: number; open: number; high: number; low: number; close: number }[],
): void {
  const directory = join(dataset, timeframe);
  mkdirSync(directory, { recursive: true });
  const rows = bars.map(
    (bar) =>
      `${stamp(bar.timeMs)},${bar.open.toFixed(5)},${bar.high.toFixed(5)},${bar.low.toFixed(5)},${bar.close.toFixed(5)},1`,
  );
  const first = bars[0];
  const fileDate = first === undefined ? "2024-01-15" : stamp(first.timeMs).slice(0, 10);
  writeFileSync(
    join(directory, `${fileDate}.csv`),
    ["time_utc,open,high,low,close,volume", ...rows].join("\n") + "\n",
  );
}

describe("gap classification", () => {
  it("does not treat a Friday-to-Monday or a daylight-saving weekend as a verified closure", () => {
    expect(classifyCandleGap(Date.UTC(2024, 0, 5, 23, 55), Date.UTC(2024, 0, 8), 5 * 60_000)).toBe(
      "UNCLASSIFIED_GAP",
    );
    expect(classifyCandleGap(Date.UTC(2024, 2, 8, 21, 55), Date.UTC(2024, 2, 11), 5 * 60_000)).toBe(
      "UNCLASSIFIED_GAP",
    );
    const dataset = root();
    mkdirSync(join(dataset, "M5"));
    writeFileSync(
      join(dataset, "M5", "2024-03-11.csv"),
      [
        "time_utc,open,high,low,close,volume",
        "2024-03-08 21:55:00,1.10000,1.10020,1.09980,1.10010,1",
        "2024-03-11 00:00:00,1.10010,1.10030,1.09990,1.10020,1",
        "",
      ].join("\n"),
    );
    const read = readClosedNativeCandles("M5", {
      root: dataset,
      lookbackBars: 10,
      asOfMs: Date.UTC(2024, 2, 11, 0, 5),
    });
    expect(read.status).toBe("ok");
    if (read.status === "ok") {
      expect(read.gaps).toHaveLength(1);
      expect(read.gaps[0]?.classification).toBe("UNCLASSIFIED_GAP");
      expect(read.gaps[0]?.missingCandles).toBeGreaterThan(1);
    }
  });

  it("does not restart indicators when the next candle is only the next timeframe step", () => {
    const step = 5 * 60_000;
    const start = Date.UTC(2024, 0, 1, 23, 0);
    const bars = Array.from({ length: 21 }, (_, index) => ({
      timeMs: start + index * step,
      open: 2,
      high: 2.01,
      low: 1.99,
      close: 2,
    }));
    const dataset = root();
    writeBars(dataset, "M5", bars);
    const analysis = analyzeInstrument("EURUSD", {
      root: dataset,
      asOfMs: start + 21 * step,
      cache: new Map(),
      lookbackBars: 30,
    });
    expect(analysis.kind).toBe("report");
    if (analysis.kind !== "report") {
      return;
    }
    const section = analysis.sections.find((item) => item.timeframe === "M5");
    expect(section?.status).toBe("ok");
    if (section?.status === "ok") {
      expect(section.candlesUsed).toBe(21);
      expect(section.indicators.ema20).toBe(2);
      expect(section.warnings.some((warning) => warning.includes("UNCLASSIFIED_GAP"))).toBe(false);
    }
  });

  it("restarts calculations after an unexpected hole and continues ATR across an expected closure", () => {
    const step = 60 * 60_000;
    const start = Date.UTC(2024, 0, 15);
    const pre = {
      timeMs: start,
      open: 1.05,
      high: 1.1,
      low: 1,
      close: 1.05,
    };
    const post = Array.from({ length: 15 }, (_, index) => ({
      timeMs: start + (index + 2) * step,
      open: 1.21,
      high: 1.22,
      low: 1.2,
      close: 1.21,
    }));
    const dataset = root();
    writeBars(dataset, "H1", [pre, ...post]);
    const asOfMs = start + 17 * step;

    const unexpected = analyzeInstrument("EURUSD", {
      root: dataset,
      asOfMs,
      cache: new Map(),
      lookbackBars: 30,
      gapClassifier: () => "UNEXPECTED_MISSING_DATA",
    });
    const expected = analyzeInstrument("EURUSD", {
      root: dataset,
      asOfMs,
      cache: new Map(),
      lookbackBars: 30,
      gapClassifier: () => "EXPECTED_MARKET_CLOSURE",
    });
    expect(unexpected.kind).toBe("report");
    expect(expected.kind).toBe("report");
    if (unexpected.kind !== "report" || expected.kind !== "report") {
      return;
    }
    const restarted = unexpected.sections.find((item) => item.timeframe === "H1");
    const continued = expected.sections.find((item) => item.timeframe === "H1");
    expect(restarted?.status).toBe("ok");
    expect(continued?.status).toBe("ok");
    if (restarted?.status !== "ok" || continued?.status !== "ok") {
      return;
    }
    expect(restarted.candlesUsed).toBe(15);
    expect(continued.candlesUsed).toBe(16);
    expect(restarted.indicators.atr14).toBeCloseTo(0.02, 10);
    expect(continued.indicators.atr14).toBeCloseTo(0.034165451895, 8);
    expect(restarted.warnings.some((warning) => warning.includes("UNEXPECTED_MISSING_DATA"))).toBe(true);
    expect(restarted.warnings.some((warning) => warning.includes("not bridged"))).toBe(true);
    expect(continued.warnings.some((warning) => warning.includes("not bridged"))).toBe(false);

    const formatted = formatInstrumentAnalysis(unexpected);
    expect(formatted).toContain("UNEXPECTED_MISSING_DATA");
    expect(formatted).toContain("not bridged");
    expect(formatted).toContain("not forward-filled");
  });

  it("restarts EMA and RSI after several missing candles instead of bridging the jump", () => {
    const step = 60 * 60_000;
    const start = Date.UTC(2024, 0, 16);
    const early = [1, 2, 3, 4, 5].map((close, index) => ({
      timeMs: start + index * step,
      open: close,
      high: close + 0.1,
      low: close - 0.1,
      close,
    }));
    const later = Array.from({ length: 20 }, (_, index) => ({
      timeMs: start + (index + 8) * step,
      open: 10,
      high: 10.1,
      low: 9.9,
      close: 10,
    }));
    const dataset = root();
    writeBars(dataset, "H1", [...early, ...later]);
    const analysis = analyzeInstrument("EURUSD", {
      root: dataset,
      asOfMs: start + 28 * step,
      cache: new Map(),
      lookbackBars: 40,
    });
    expect(analysis.kind).toBe("report");
    if (analysis.kind !== "report") {
      return;
    }
    const section = analysis.sections.find((item) => item.timeframe === "H1");
    expect(section?.status).toBe("ok");
    if (section?.status === "ok") {
      expect(section.candlesUsed).toBe(20);
      expect(section.indicators.ema20).toBe(10);
      expect(section.indicators.rsi14).toBe(50);
      expect(section.warnings.some((warning) => warning.includes("UNCLASSIFIED_GAP"))).toBe(true);
    }
    expect(calculationStartIndex(
      [...early, ...later],
      [{ afterTimeMs: later[0]?.timeMs ?? 0, missingCandles: 3, classification: "UNCLASSIFIED_GAP" }],
    )).toBe(5);
  });
});
