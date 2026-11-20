import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import {
  DEFAULT_NATIVE_DATASET_ROOT,
  parseNativeCsv,
  type Candle,
  type CandleCacheEntry,
} from "../market/nativeCandles.js";
import { findTimeframe, type TimeframeId } from "../market/timeframes.js";
import type { BacktestCandle } from "./types.js";

export const M1_DATASET_DIR = resolve(
  "services/dukascopy-downloader/output/EURUSD_M1_3y_production/EURUSD_M1_3y_staging",
);

const fileCache = new Map<string, CandleCacheEntry>();

export function loadClosedCandles(input: {
  timeframe: TimeframeId;
  startMs: number;
  endMs: number;
  root?: string;
  m1Root?: string;
}): { ok: true; candles: BacktestCandle[]; m1: BacktestCandle[]; warnings: string[] } | { ok: false; reason: string } {
  const timeframe = findTimeframe(input.timeframe);
  if (timeframe === undefined) {
    return { ok: false, reason: `${input.timeframe} is not a supported timeframe.` };
  }
  const directory = input.timeframe === "M1"
    ? (input.m1Root ?? M1_DATASET_DIR)
    : join(input.root ?? DEFAULT_NATIVE_DATASET_ROOT, input.timeframe);
  const candles = readDirectory(directory, input.startMs, input.endMs, timeframe.minutes * 60_000);
  if (!candles.ok) {
    return candles;
  }
  if (input.timeframe === "M1") {
    return {
      ok: true,
      candles: candles.candles,
      m1: [],
      warnings: ["M1 has no finer candles, so stop and target use that minute's high and low."],
    };
  }
  const m1 = readDirectory(input.m1Root ?? M1_DATASET_DIR, input.startMs, input.endMs, 60_000);
  if (!m1.ok) {
    return {
      ok: true,
      candles: candles.candles,
      m1: [],
      warnings: [`M1 execution candles were not loaded (${m1.reason}). Parent bars are used for stops and targets.`],
    };
  }
  return { ok: true, candles: candles.candles, m1: m1.candles, warnings: [] };
}

function readDirectory(
  directory: string,
  startMs: number,
  endMs: number,
  durationMs: number,
): { ok: true; candles: BacktestCandle[] } | { ok: false; reason: string } {
  let names: string[];
  try {
    names = readdirSync(directory).filter((name) => /^\d{4}-\d{2}-\d{2}\.csv$/.test(name)).sort();
  } catch {
    return { ok: false, reason: "Historical candles are not available in the requested directory." };
  }
  const startDate = new Date(startMs).toISOString().slice(0, 10);
  const endDate = new Date(endMs - 1).toISOString().slice(0, 10);
  const chosen = names.filter((name) => name.slice(0, 10) >= startDate && name.slice(0, 10) <= endDate);
  const candles: BacktestCandle[] = [];
  for (const name of chosen) {
    let parsed: Candle[];
    try {
      parsed = readCached(join(directory, name), durationMs);
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : "invalid CSV";
      return { ok: false, reason: `${name}: ${message}` };
    }
    for (const candle of parsed) {
      if (candle.timeMs >= startMs && candle.timeMs + durationMs <= endMs) {
        candles.push({
          timeMs: candle.timeMs,
          open: candle.open,
          high: candle.high,
          low: candle.low,
          close: candle.close,
        });
      }
    }
  }
  return { ok: true, candles };
}

function readCached(file: string, durationMs: number): Candle[] {
  const stat = statSync(file);
  const cached = fileCache.get(file);
  if (cached !== undefined && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size) {
    return cached.candles;
  }
  const candles = parseNativeCsv(readFileSync(file, "utf8"), durationMs);
  fileCache.set(file, { mtimeMs: stat.mtimeMs, size: stat.size, candles });
  return candles;
}
