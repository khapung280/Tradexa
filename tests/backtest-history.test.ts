import { existsSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { formatBacktestReport } from "../src/backtest/format.ts";
import { M1_DATASET_DIR, loadClosedCandles } from "../src/backtest/load.ts";
import { runBacktest } from "../src/backtest/run.ts";
import { DEFAULT_NATIVE_DATASET_ROOT } from "../src/market/nativeCandles.ts";

const startMs = Date.UTC(2024, 5, 3);
const endMs = Date.UTC(2024, 5, 8);
const h1Sample = join(DEFAULT_NATIVE_DATASET_ROOT, "H1", "2024-06-03.csv");
const m1Sample = join(M1_DATASET_DIR, "2024-06-03.csv");
const datasetReady = existsSync(h1Sample) && existsSync(m1Sample);

describe("bounded EURUSD historical simulation", () => {
  it.skipIf(!datasetReady)("replays H1 EMA crosses on the local candles without rewriting them", () => {
    const before = statSync(h1Sample);
    const started = performance.now();
    const loaded = loadClosedCandles({ timeframe: "H1", startMs, endMs });
    const outcome = runBacktest({
      symbol: "EURUSD",
      strategy: "EMA_CROSSOVER",
      timeframe: "H1",
      startMs,
      endMs,
      sizing: { mode: "fixed", lots: 0.1 },
      stopLossPips: 30,
      takeProfitPips: 60,
      spreadPips: 1,
      slippagePips: 0,
    });
    const elapsed = performance.now() - started;
    expect(loaded.ok).toBe(true);
    expect(outcome.ok).toBe(true);
    if (!loaded.ok || !outcome.ok) {
      return;
    }
    expect(loaded.candles.length).toBeGreaterThan(20);
    expect(outcome.result.candlesUsed).toBe(loaded.candles.length);
    expect(elapsed).toBeLessThan(20_000);
    for (const trade of outcome.result.trades) {
      const signalIndex = loaded.candles.findIndex((candle) => candle.timeMs === trade.signalTimeMs);
      const next = loaded.candles[signalIndex + 1];
      expect(signalIndex).toBeGreaterThanOrEqual(0);
      expect(next?.timeMs).toBe(trade.entryTimeMs);
      expect(trade.entryTimeMs).toBeGreaterThan(trade.signalTimeMs);
      const expectedFill = trade.side === "buy" ? (next?.open ?? 0) + 0.0001 : next?.open ?? 0;
      expect(trade.entryFill).toBeCloseTo(expectedFill, 6);
      expect(trade.exitTimeMs).toBeGreaterThanOrEqual(trade.entryTimeMs);
    }
    expect(outcome.result.finalBalance).toBeCloseTo(
      outcome.result.initialBalance + outcome.result.metrics.netProfit,
      6,
    );
    const report = formatBacktestReport(outcome.result);
    expect(report).toContain("historical simulation");
    expect(report).toContain("not future performance");
    expect(JSON.stringify(outcome.result.metrics)).not.toMatch(/NaN|Infinity/);
    expect(statSync(h1Sample).mtimeMs).toBe(before.mtimeMs);
    expect(statSync(h1Sample).size).toBe(before.size);
  });
});
