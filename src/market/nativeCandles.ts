import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { classifyCandleGap, gapWarning, type CandleGap, type GapClass } from "../analysis/gaps.js";
import { findTimeframe } from "./timeframes.js";

/** Native Dukascopy timeframes that already exist as CSV. M1 is not read. */
export const NATIVE_ANALYSIS_TIMEFRAMES = ["M5", "M15", "M30", "H1", "H4", "D1"] as const;
export type NativeAnalysisTimeframe = (typeof NATIVE_ANALYSIS_TIMEFRAMES)[number];

export const NATIVE_CSV_HEADER = "time_utc,open,high,low,close,volume";

/**
 * Only EURUSD has a completed native dataset in this repository.
 * The folder is the instrument dataset, not a parent of several instruments.
 */
export const DEFAULT_NATIVE_DATASET_ROOT = resolve(
  "services/dukascopy-downloader/output/EURUSD_native_timeframes_3y",
);

const DATE_FILE = /^(\d{4})-(\d{2})-(\d{2})\.csv$/;
const NUMBER_TEXT = /^-?(?:\d+\.\d+|\d+)(?:[eE][+-]?\d+)?$/;
const MINUTE_MS = 60_000;

export type Candle = {
  timeMs: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
};

export type CandleCacheEntry = {
  mtimeMs: number;
  size: number;
  candles: Candle[];
};

export type GapClassifier = (previousTimeMs: number, currentTimeMs: number, durationMs: number) => GapClass;

export type CandleReadOptions = {
  root: string;
  lookbackBars: number;
  asOfMs: number;
  /** Omit to skip caching. A map is reused only when mtime and size still match. */
  cache?: Map<string, CandleCacheEntry> | null;
  /**
   * Defaults to the unclassified classifier. A test or a verified calendar
   * adapter may supply another classifier. The default does not infer weekends.
   */
  gapClassifier?: GapClassifier;
};

export type CandleRead =
  | {
      status: "ok";
      timeframe: NativeAnalysisTimeframe;
      candles: Candle[];
      gaps: CandleGap[];
      warnings: string[];
      filesRead: number;
    }
  | {
      status: "unavailable";
      timeframe: NativeAnalysisTimeframe;
      reason: string;
    };

export function isNativeAnalysisTimeframe(value: string): value is NativeAnalysisTimeframe {
  return (NATIVE_ANALYSIS_TIMEFRAMES as readonly string[]).includes(value);
}

export function eurusdDatasetRoot(override?: string): string {
  if (override !== undefined && override.length > 0) {
    return override;
  }
  const fromEnv = process.env.NATIVE_DATASET_ROOT;
  if (fromEnv !== undefined && fromEnv.length > 0) {
    return fromEnv;
  }
  return DEFAULT_NATIVE_DATASET_ROOT;
}

export function timeframeDurationMs(timeframe: NativeAnalysisTimeframe): number {
  const found = findTimeframe(timeframe);
  if (found === undefined) {
    throw new Error(`Unknown timeframe ${timeframe}.`);
  }
  return found.minutes * MINUTE_MS;
}

export function formatUtcTimestamp(timeMs: number): string {
  const date = new Date(timeMs);
  const pad = (value: number): string => String(value).padStart(2, "0");
  return (
    `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())} ` +
    `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}:${pad(date.getUTCSeconds())} UTC`
  );
}

export function parseNativeTimestamp(value: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(value);
  if (match === null) {
    return null;
  }
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > 31 ||
    hour > 23 ||
    minute > 59 ||
    second > 59
  ) {
    return null;
  }
  const timeMs = Date.UTC(year, month - 1, day, hour, minute, second);
  const check = new Date(timeMs);
  if (
    check.getUTCFullYear() !== year ||
    check.getUTCMonth() !== month - 1 ||
    check.getUTCDate() !== day ||
    check.getUTCHours() !== hour ||
    check.getUTCMinutes() !== minute ||
    check.getUTCSeconds() !== second
  ) {
    return null;
  }
  return timeMs;
}

/**
 * Read-only parser for the Java downloader's CSV.
 * Invalid rows throw. Nothing is written and missing candles are not invented.
 */
export function parseNativeCsv(text: string, durationMs: number): Candle[] {
  if (!Number.isInteger(durationMs) || durationMs <= 0) {
    throw new Error("Timeframe duration must be a positive integer.");
  }
  if (text.startsWith("\uFEFF") || text.includes("\r")) {
    throw new Error("Candle CSV is not LF UTF-8.");
  }
  if (!text.endsWith("\n")) {
    throw new Error("Candle CSV is incomplete.");
  }
  const lines = text.split("\n");
  if (lines[lines.length - 1] !== "") {
    throw new Error("Candle CSV is incomplete.");
  }
  if (lines[0] !== NATIVE_CSV_HEADER) {
    throw new Error("Candle CSV header is wrong.");
  }

  const candles: Candle[] = [];
  for (let row = 1; row < lines.length - 1; row += 1) {
    const line = lines[row];
    if (line === undefined || line.length === 0) {
      throw new Error(`Candle CSV row ${row + 1} is malformed.`);
    }
    const parts = line.split(",");
    if (parts.length !== 6) {
      throw new Error(`Candle CSV row ${row + 1} is malformed.`);
    }
    const timeText = parts[0] ?? "";
    const timeMs = parseNativeTimestamp(timeText);
    if (timeMs === null || timeMs % durationMs !== 0) {
      throw new Error(`Candle CSV row ${row + 1} has an invalid timestamp.`);
    }
    const open = parseCsvNumber(parts[1], row);
    const high = parseCsvNumber(parts[2], row);
    const low = parseCsvNumber(parts[3], row);
    const close = parseCsvNumber(parts[4], row);
    const volume = parseCsvNumber(parts[5], row);
    if (open <= 0 || high <= 0 || low <= 0 || close <= 0 || volume < 0) {
      throw new Error(`Candle CSV row ${row + 1} has an invalid price or volume.`);
    }
    if (high < low || high < open || high < close || low > open || low > close) {
      throw new Error(`Candle CSV row ${row + 1} has invalid OHLC relationships.`);
    }
    const previous = candles[candles.length - 1];
    if (previous !== undefined) {
      if (timeMs === previous.timeMs) {
        throw new Error(`Candle CSV row ${row + 1} duplicates a timestamp.`);
      }
      if (timeMs < previous.timeMs) {
        throw new Error(`Candle CSV row ${row + 1} is out of order.`);
      }
    }
    candles.push({ timeMs, open, high, low, close, volume });
  }
  return candles;
}

/**
 * Loads date files at or before the UTC date of `asOfMs`, walking backward
 * until `lookbackBars` closed candles are available. Files dated after that
 * UTC date are not opened.
 */
export function readClosedNativeCandles(
  timeframe: NativeAnalysisTimeframe,
  options: CandleReadOptions,
): CandleRead {
  if (!Number.isInteger(options.lookbackBars) || options.lookbackBars < 1) {
    return {
      status: "unavailable",
      timeframe,
      reason: `${timeframe} lookback must be a positive number of candles.`,
    };
  }
  if (!Number.isFinite(options.asOfMs)) {
    return {
      status: "unavailable",
      timeframe,
      reason: `${timeframe} analysis timestamp is invalid.`,
    };
  }

  const directory = join(options.root, timeframe);
  let names: string[];
  try {
    names = readdirSync(directory)
      .filter((name) => DATE_FILE.test(name))
      .sort();
  } catch (error: unknown) {
    const code = errorCode(error);
    if (code === "ENOENT" || code === "ENOTDIR") {
      return {
        status: "unavailable",
        timeframe,
        reason: `${timeframe} dataset is not available.`,
      };
    }
    return {
      status: "unavailable",
      timeframe,
      reason: `${timeframe} dataset could not be read.`,
    };
  }
  if (names.length === 0) {
    return {
      status: "unavailable",
      timeframe,
      reason: `${timeframe} dataset has no CSV files.`,
    };
  }

  const durationMs = timeframeDurationMs(timeframe);
  const asOfDate = utcDate(options.asOfMs);
  const eligible = names.filter((name) => name.slice(0, 10) <= asOfDate);
  if (eligible.length === 0) {
    return {
      status: "unavailable",
      timeframe,
      reason: `${timeframe} has no candles on or before the analysis date.`,
    };
  }

  const chosen: { name: string; candles: Candle[] }[] = [];
  let closedCount = 0;
  for (let index = eligible.length - 1; index >= 0 && closedCount < options.lookbackBars; index -= 1) {
    const name = eligible[index];
    if (name === undefined) {
      continue;
    }
    try {
      const loaded = readCachedFile(join(directory, name), durationMs, options.cache ?? null);
      chosen.push({ name, candles: loaded });
      for (const candle of loaded) {
        if (candle.timeMs + durationMs <= options.asOfMs) {
          closedCount += 1;
        }
      }
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : "invalid CSV";
      return {
        status: "unavailable",
        timeframe,
        reason: `${timeframe} ${name}: ${message}`,
      };
    }
  }
  chosen.reverse();

  const parsed: Candle[] = [];
  for (const file of chosen) {
    const previous = parsed[parsed.length - 1];
    const first = file.candles[0];
    if (previous !== undefined && first !== undefined) {
      if (first.timeMs === previous.timeMs) {
        return {
          status: "unavailable",
          timeframe,
          reason: `${timeframe} has a duplicate timestamp across CSV files.`,
        };
      }
      if (first.timeMs < previous.timeMs) {
        return {
          status: "unavailable",
          timeframe,
          reason: `${timeframe} CSV files are not in ascending time order.`,
        };
      }
    }
    parsed.push(...file.candles);
  }

  const closed = parsed.filter((candle) => candle.timeMs + durationMs <= options.asOfMs);
  const candles = closed.slice(-options.lookbackBars);
  if (candles.length === 0) {
    return {
      status: "unavailable",
      timeframe,
      reason: `${timeframe} has no candle that had closed at the analysis timestamp.`,
    };
  }

  const classifier = options.gapClassifier ?? classifyCandleGap;
  const gaps = findGaps(candles, durationMs, classifier);
  const warnings = gapWarningLines(gaps);
  if (closed.length < options.lookbackBars) {
    warnings.push(
      `Only ${closed.length} closed candles are available at or before the analysis timestamp. The requested lookback is ${options.lookbackBars}.`,
    );
  }

  return {
    status: "ok",
    timeframe,
    candles,
    gaps,
    warnings,
    filesRead: chosen.length,
  };
}

function utcDate(timeMs: number): string {
  return new Date(timeMs).toISOString().slice(0, 10);
}

function findGaps(
  candles: readonly Candle[],
  durationMs: number,
  classifier: GapClassifier,
): CandleGap[] {
  const gaps: CandleGap[] = [];
  for (let index = 1; index < candles.length; index += 1) {
    const previous = candles[index - 1];
    const current = candles[index];
    if (previous === undefined || current === undefined) {
      continue;
    }
    const missing = Math.round((current.timeMs - previous.timeMs) / durationMs) - 1;
    if (missing > 0) {
      gaps.push({
        afterTimeMs: current.timeMs,
        missingCandles: missing,
        classification: classifier(previous.timeMs, current.timeMs, durationMs),
      });
    }
  }
  return gaps;
}

function gapWarningLines(gaps: readonly CandleGap[]): string[] {
  const lines = gaps.map((gap) => gapWarning(gap, formatUtcTimestamp(gap.afterTimeMs)));
  if (lines.length <= 3) {
    return lines;
  }
  const first = lines[0] ?? "";
  return [`${lines.length} gaps in the loaded window. ${first} Remaining gaps were not filled.`];
}

function readCachedFile(
  path: string,
  durationMs: number,
  cache: Map<string, CandleCacheEntry> | null,
): Candle[] {
  const stat = statSync(path);
  const cached = cache?.get(path);
  if (cached !== undefined && cached.mtimeMs === stat.mtimeMs && cached.size === stat.size) {
    return cached.candles;
  }
  const text = readFileSync(path, "utf8");
  const candles = parseNativeCsv(text, durationMs);
  cache?.set(path, { mtimeMs: stat.mtimeMs, size: stat.size, candles });
  return candles;
}

function parseCsvNumber(value: string | undefined, row: number): number {
  if (value === undefined || !NUMBER_TEXT.test(value)) {
    throw new Error(`Candle CSV row ${row + 1} has a non-numeric value.`);
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Candle CSV row ${row + 1} has a non-finite value.`);
  }
  return parsed;
}

function errorCode(error: unknown): string | undefined {
  if (typeof error === "object" && error !== null && "code" in error) {
    const code = error.code;
    return typeof code === "string" ? code : undefined;
  }
  return undefined;
}
