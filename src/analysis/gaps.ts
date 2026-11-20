/**
 * Gap policy.
 *
 * This repository does not contain a verified Dukascopy trading calendar.
 * City session hours are not a Friday-close schedule, and the downloader
 * records an empty response as "not a confirmed market closure." Because of
 * that, the default classifier returns UNCLASSIFIED_GAP for every hole.
 * It does not guess that a Saturday or a daylight-saving weekend is closed.
 *
 * EXPECTED_MARKET_CLOSURE is accepted only when a caller supplies a classifier
 * that already verified the closure. Those gaps stay in one continuous series:
 * EMA, RSI, MACD, and ATR do not restart, and ATR keeps the previous close so
 * the closure's price movement remains in the true range.
 *
 * UNEXPECTED_MISSING_DATA and UNCLASSIFIED_GAP are not treated as continuous
 * history. EMA, RSI, MACD, ATR, trend, market structure, and support or
 * resistance zones are calculated only on the candles after the latest such
 * gap. Their warm-up starts again. Each timeframe is classified on its own,
 * so a gap on one timeframe does not reset the others. A normal one-bar step,
 * including midnight, is not a gap and does not restart anything.
 */

export const GAP_CLASSES = [
  "EXPECTED_MARKET_CLOSURE",
  "UNEXPECTED_MISSING_DATA",
  "UNCLASSIFIED_GAP",
] as const;
export type GapClass = (typeof GAP_CLASSES)[number];

export const NO_VERIFIED_CALENDAR_REASON =
  "No verified Dukascopy trading calendar is available, so this gap is not called a market closure.";

export type CandleGap = {
  afterTimeMs: number;
  missingCandles: number;
  classification: GapClass;
};

export function classifyCandleGap(
  _previousTimeMs: number,
  _currentTimeMs: number,
  _durationMs: number,
): GapClass {
  return "UNCLASSIFIED_GAP";
}

export function gapWarning(gap: CandleGap, timestamp: string): string {
  const base = `Gap of ${gap.missingCandles} candle(s) before ${timestamp}. Prices were not forward-filled. Classification: ${gap.classification}.`;
  if (gap.classification === "EXPECTED_MARKET_CLOSURE") {
    return `${base} Indicators continue across this closure, and ATR keeps the previous close.`;
  }
  if (gap.classification === "UNEXPECTED_MISSING_DATA") {
    return `${base} This in-session hole is not continuous history. Calculations restart after the latest unexpected or unclassified gap.`;
  }
  return `${base} ${NO_VERIFIED_CALENDAR_REASON} Calculations restart after the latest unexpected or unclassified gap.`;
}

/** Index of the first candle that smoothed indicators and structure may use. */
export function calculationStartIndex(
  candles: readonly { timeMs: number }[],
  gaps: readonly CandleGap[],
): number {
  let start = 0;
  for (const gap of gaps) {
    if (gap.classification === "EXPECTED_MARKET_CLOSURE") {
      continue;
    }
    const index = candles.findIndex((candle) => candle.timeMs === gap.afterTimeMs);
    if (index > start) {
      start = index;
    }
  }
  return start;
}
