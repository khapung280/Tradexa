import { existsSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { analyzeInstrument } from "../src/analysis/technical.ts";
import { DEFAULT_NATIVE_DATASET_ROOT } from "../src/market/nativeCandles.ts";

const sample = join(DEFAULT_NATIVE_DATASET_ROOT, "D1", "2026-11-16.csv");
const datasetReady = existsSync(sample);

describe("EURUSD native dataset benchmark", () => {
  it.skipIf(!datasetReady)("analyzes the bounded native window without rewriting a CSV", () => {
    const before = statSync(sample);
    const bytes = readFileSync(sample);
    const cache = new Map();
    const asOfMs = Date.UTC(2026, 10, 20, 4, 40, 0);
    const firstStarted = performance.now();
    const first = analyzeInstrument("EURUSD", { asOfMs, cache, lookbackBars: 400 });
    const firstMs = performance.now() - firstStarted;
    const secondStarted = performance.now();
    const second = analyzeInstrument("EURUSD", { asOfMs, cache, lookbackBars: 400 });
    const secondMs = performance.now() - secondStarted;

    expect(first.kind).toBe("report");
    expect(second.kind).toBe("report");
    if (first.kind !== "report" || second.kind !== "report") {
      return;
    }
    expect(first.sections).toHaveLength(6);
    for (const section of first.sections) {
      expect(section.status).toBe("ok");
      if (section.status !== "ok") {
        continue;
      }
      expect(section.candleCloseMs).toBeLessThanOrEqual(asOfMs);
      expect(section.candlesUsed).toBeLessThanOrEqual(400);
      expect(section.filesRead).toBeLessThan(500);
    }
    const daily = first.sections.find((section) => section.timeframe === "D1");
    expect(daily?.status).toBe("ok");
    if (daily?.status === "ok") {
      expect(daily.filesRead).toBeLessThanOrEqual(402);
      expect(daily.indicators.ema200).not.toBeNull();
      expect(daily.warnings.join(" ")).toMatch(/unverified/);
    }
    expect(firstMs).toBeLessThan(5_000);
    expect(secondMs).toBeLessThan(firstMs);
    expect(statSync(sample).mtimeMs).toBe(before.mtimeMs);
    expect(statSync(sample).size).toBe(before.size);
    expect(readFileSync(sample).equals(bytes)).toBe(true);
    console.log(`benchmark_first_ms=${firstMs.toFixed(1)} benchmark_second_ms=${secondMs.toFixed(1)}`);
  });
});
