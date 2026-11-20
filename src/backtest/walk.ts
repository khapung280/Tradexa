import type { BacktestCandle, BacktestResult, WalkForwardFold } from "./types.js";
import { simulate, type SimulationInput } from "./engine.js";

export function walkForward(input: SimulationInput, folds = 3, inSampleFraction = 0.7): WalkForwardFold[] {
  if (!Number.isInteger(folds) || folds < 1) {
    throw new Error("Walk-forward folds must be a positive integer.");
  }
  if (!(inSampleFraction > 0) || !(inSampleFraction < 1)) {
    throw new Error("In-sample fraction must be between 0 and 1.");
  }
  const candles = input.candles;
  if (candles.length === 0) {
    return [];
  }
  const first = candles[0]?.timeMs ?? input.startMs;
  const last = candles[candles.length - 1]?.timeMs ?? input.endMs;
  const span = Math.max(1, last - first);
  const foldSpan = span / folds;
  const results: WalkForwardFold[] = [];
  for (let fold = 0; fold < folds; fold += 1) {
    const foldStart = first + fold * foldSpan;
    const foldEnd = fold === folds - 1 ? last + input.settings.durationMs : first + (fold + 1) * foldSpan;
    const split = foldStart + (foldEnd - foldStart) * inSampleFraction;
    results.push({
      fold: fold + 1,
      inSample: runSlice(input, foldStart, split, "in_sample"),
      outOfSample: runSlice(input, split, foldEnd, "out_of_sample"),
    });
  }
  return results;
}

function runSlice(
  input: SimulationInput,
  startMs: number,
  endMs: number,
  label: BacktestResult["label"],
): BacktestResult {
  const candles = input.candles.filter((candle) => candle.timeMs >= startMs && candle.timeMs + input.settings.durationMs <= endMs);
  const m1 = input.m1 === null
    ? null
    : input.m1.filter((candle) => candle.timeMs >= startMs && candle.timeMs < endMs);
  return simulate({
    ...input,
    candles,
    m1,
    startMs,
    endMs,
    label,
  });
}

export function sliceCandles(
  candles: readonly BacktestCandle[],
  startMs: number,
  endMs: number,
  durationMs: number,
): BacktestCandle[] {
  return candles.filter((candle) => candle.timeMs >= startMs && candle.timeMs + durationMs <= endMs);
}
