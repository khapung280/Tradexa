import { findPair } from "../risk/pairs.js";
import {
  eurusdDatasetRoot,
  formatUtcTimestamp,
  NATIVE_ANALYSIS_TIMEFRAMES,
  readClosedNativeCandles,
  timeframeDurationMs,
  type CandleCacheEntry,
  type NativeAnalysisTimeframe,
} from "../market/nativeCandles.js";
import {
  atr,
  atrVolatility,
  ema,
  emaCrossover,
  macd,
  macdCrossover,
  rsi,
  type Crossover,
  type VolatilityLabel,
} from "./indicators.js";
import {
  DEFAULT_PIVOT_LEFT,
  DEFAULT_PIVOT_RIGHT,
  detectStructure,
  type StructureResult,
} from "./structure.js";
import { classifyTrend, type TrendAssessment } from "./trend.js";
import { calculationStartIndex, type GapClass } from "./gaps.js";
import { detectZones, type ZoneSet } from "./zones.js";

/** Enough closed candles for EMA 200, with extra bars for swings and zones. */
export const ANALYSIS_LOOKBACK_BARS = 400;

const STALE_CANDLE_MS = 3 * 24 * 60 * 60 * 1000;

const sharedCache = new Map<string, CandleCacheEntry>();

export type AnalysisOptions = {
  root?: string;
  asOfMs?: number;
  lookbackBars?: number;
  cache?: Map<string, CandleCacheEntry> | null;
  pivotLeft?: number;
  pivotRight?: number;
  gapClassifier?: (previousTimeMs: number, currentTimeMs: number, durationMs: number) => GapClass;
};

export type IndicatorSnapshot = {
  ema20: number | null;
  ema50: number | null;
  ema200: number | null;
  emaCrossover: Crossover;
  rsi14: number | null;
  macd: number | null;
  macdSignal: number | null;
  macdHistogram: number | null;
  macdCrossover: Crossover;
  atr14: number | null;
  volatility: VolatilityLabel;
};

export type TimeframeSection =
  | {
      status: "unavailable";
      timeframe: NativeAnalysisTimeframe;
      reason: string;
    }
  | {
      status: "ok";
      timeframe: NativeAnalysisTimeframe;
      candleOpenMs: number;
      candleCloseMs: number;
      close: number;
      candlesUsed: number;
      filesRead: number;
      indicators: IndicatorSnapshot;
      trend: TrendAssessment;
      structure: StructureResult;
      zones: ZoneSet;
      warnings: string[];
    };

export type InstrumentAnalysis =
  | { kind: "unsupported"; symbol: string; text: string }
  | { kind: "unavailable"; symbol: string; text: string }
  | {
      kind: "report";
      symbol: string;
      asOfMs: number;
      sections: TimeframeSection[];
      summary: string[];
    };

export function analyzeInstrument(rawSymbol: string, options: AnalysisOptions = {}): InstrumentAnalysis {
  const symbol = (findPair(rawSymbol)?.symbol ?? rawSymbol.trim().toUpperCase());
  if (findPair(rawSymbol) === undefined) {
    return {
      kind: "unsupported",
      symbol,
      text: `${symbol} is not a supported instrument. Tradexa did not estimate prices for it.`,
    };
  }
  if (symbol !== "EURUSD") {
    return {
      kind: "unavailable",
      symbol,
      text: `${symbol} is a known pair, but this installation has no native historical candles for it. Native candles are available for EURUSD only. No prices were estimated.`,
    };
  }

  const asOfMs = options.asOfMs ?? Date.now();
  const lookbackBars = options.lookbackBars ?? ANALYSIS_LOOKBACK_BARS;
  const root = eurusdDatasetRoot(options.root);
  const cache = options.cache === undefined ? sharedCache : options.cache;
  const sections = NATIVE_ANALYSIS_TIMEFRAMES.map((timeframe) =>
    analyzeTimeframe(timeframe, { ...options, asOfMs, lookbackBars, root, cache }),
  );
  return {
    kind: "report",
    symbol,
    asOfMs,
    sections,
    summary: summarize(sections),
  };
}

export function analyzeTimeframe(
  timeframe: NativeAnalysisTimeframe,
  options: AnalysisOptions & { root: string; asOfMs: number; lookbackBars: number },
): TimeframeSection {
  const read = readClosedNativeCandles(timeframe, {
    root: options.root,
    lookbackBars: options.lookbackBars,
    asOfMs: options.asOfMs,
    cache: options.cache ?? null,
    ...(options.gapClassifier !== undefined ? { gapClassifier: options.gapClassifier } : {}),
  });
  if (read.status === "unavailable") {
    return { status: "unavailable", timeframe, reason: read.reason };
  }

  const loaded = read.candles;
  const start = calculationStartIndex(loaded, read.gaps);
  const candles = loaded.slice(start);
  const closes = candles.map((candle) => candle.close);
  const ema20 = ema(closes, 20);
  const ema50 = ema(closes, 50);
  const ema200 = ema(closes, 200);
  const rsiValues = rsi(closes, 14);
  const macdPoints = macd(closes, 12, 26, 9);
  const atrValues = atr(candles, 14);
  const last = candles.length - 1;
  const candle = candles[last];
  if (candle === undefined) {
    return {
      status: "unavailable",
      timeframe,
      reason: `${timeframe} has no closed candle.`,
    };
  }

  const warnings = [...read.warnings];
  if (start > 0) {
    warnings.push(
      `Calculations use ${candles.length} candles after the latest unexpected or unclassified gap. ${start} earlier closed candles were not bridged into EMA, RSI, MACD, ATR, trend, structure, or zones.`,
    );
  }
  if (candles.length < 200) {
    warnings.push("Fewer than 200 closed candles are available, so EMA 200 is not calculated.");
  }
  const candleCloseMs = candle.timeMs + timeframeDurationMs(timeframe);
  if (options.asOfMs - candleCloseMs > STALE_CANDLE_MS) {
    warnings.push(
      "The newest closed candle is more than 3 days before the analysis timestamp. This is historical data, not a live quote.",
    );
  }
  if (timeframe === "D1") {
    warnings.push(
      "D1 volume is not used. Cross-timeframe D1 volume semantics remain unverified.",
    );
  }

  const lastMacd = macdPoints[last];
  const structure = detectStructure(
    candles,
    options.pivotLeft ?? DEFAULT_PIVOT_LEFT,
    options.pivotRight ?? DEFAULT_PIVOT_RIGHT,
  );
  const atr14 = atrValues[last] ?? null;
  return {
    status: "ok",
    timeframe,
    candleOpenMs: candle.timeMs,
    candleCloseMs,
    close: candle.close,
    candlesUsed: candles.length,
    filesRead: read.filesRead,
    indicators: {
      ema20: ema20[last] ?? null,
      ema50: ema50[last] ?? null,
      ema200: ema200[last] ?? null,
      emaCrossover: emaCrossover(ema20, ema50),
      rsi14: rsiValues[last] ?? null,
      macd: lastMacd?.macd ?? null,
      macdSignal: lastMacd?.signal ?? null,
      macdHistogram: lastMacd?.histogram ?? null,
      macdCrossover: macdCrossover(macdPoints),
      atr14,
      volatility: atrVolatility(atrValues),
    },
    trend: classifyTrend(candles),
    structure,
    zones: detectZones(structure.swings, candle.close, atr14, timeframe),
    warnings,
  };
}

function summarize(sections: readonly TimeframeSection[]): string[] {
  const lines: string[] = [];
  const missing = sections.filter((section) => section.status === "unavailable");
  if (missing.length > 0) {
    lines.push(
      `Unavailable timeframes: ${missing
        .map((section) => `${section.timeframe} (${section.reason})`)
        .join("; ")}.`,
    );
  }

  const classes = sections.flatMap((section) => {
    if (section.status !== "ok") {
      return [];
    }
    const classification = section.trend.classification;
    if (classification === "insufficient_data") {
      return [];
    }
    return [{ timeframe: section.timeframe, classification }];
  });
  const ordered = [...classes].sort(
    (left, right) => summaryRank(left.timeframe) - summaryRank(right.timeframe),
  );
  const bullish = ordered.filter((item) => item.classification === "bullish").map((item) => item.timeframe);
  const bearish = ordered.filter((item) => item.classification === "bearish").map((item) => item.timeframe);
  if (bullish.length > 0 && bearish.length > 0) {
    lines.push(`Timeframes conflict: bullish ${bullish.join(", ")}; bearish ${bearish.join(", ")}.`);
  } else if (ordered.length > 0 && ordered.every((item) => item.classification === "bullish")) {
    lines.push(`Available trend classes agree: bullish (${ordered.map((item) => item.timeframe).join(", ")}).`);
  } else if (ordered.length > 0 && ordered.every((item) => item.classification === "bearish")) {
    lines.push(`Available trend classes agree: bearish (${ordered.map((item) => item.timeframe).join(", ")}).`);
  } else if (ordered.length > 0 && ordered.every((item) => item.classification === "sideways")) {
    lines.push(`Available trend classes agree: sideways (${ordered.map((item) => item.timeframe).join(", ")}).`);
  } else if (ordered.length === 0) {
    lines.push("No timeframe has enough closed history for a trend class.");
  } else {
    lines.push("Available trend classes do not share one direction.");
  }

  const higherNames = namedClasses(sections, ["D1", "H4", "H1"]);
  const lowerNames = namedClasses(sections, ["M30", "M15", "M5"]);
  const higherClasses = higherNames.map((item) => item.classification);
  const lowerClasses = lowerNames.map((item) => item.classification);
  const higherLabel = higherNames.map((item) => item.timeframe).join(", ");
  const higherVerb = higherNames.length === 1 ? "is" : "are";
  if (higherClasses.length > 0 && higherClasses.every((item) => item === "bullish") && lowerClasses.includes("bearish")) {
    lines.push(
      `${higherLabel} ${higherVerb} bullish while a lower timeframe is bearish. That is a lower-timeframe move against the higher-timeframe direction, not a trade.`,
    );
  } else if (
    higherClasses.length > 0 &&
    higherClasses.every((item) => item === "bearish") &&
    lowerClasses.includes("bullish")
  ) {
    lines.push(
      `${higherLabel} ${higherVerb} bearish while a lower timeframe is bullish. That is a lower-timeframe move against the higher-timeframe direction, not a trade.`,
    );
  }
  lines.push("These comparisons use completed candles only.");
  return lines;
}

const SUMMARY_RANK = ["D1", "H4", "H1", "M30", "M15", "M5"] as const;

function summaryRank(timeframe: NativeAnalysisTimeframe): number {
  const rank = SUMMARY_RANK.indexOf(timeframe);
  return rank === -1 ? SUMMARY_RANK.length : rank;
}

function namedClasses(
  sections: readonly TimeframeSection[],
  timeframes: readonly NativeAnalysisTimeframe[],
): { timeframe: NativeAnalysisTimeframe; classification: string }[] {
  const classes: { timeframe: NativeAnalysisTimeframe; classification: string }[] = [];
  for (const timeframe of timeframes) {
    const section = sections.find((item) => item.timeframe === timeframe);
    if (section === undefined || section.status !== "ok") {
      continue;
    }
    if (section.trend.classification === "insufficient_data") {
      continue;
    }
    classes.push({ timeframe, classification: section.trend.classification });
  }
  return classes;
}

export function analysisTimestamp(asOfMs: number): string {
  return formatUtcTimestamp(asOfMs);
}
