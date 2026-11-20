import type { Candle } from "../market/nativeCandles.js";
import { atr, ema, rsi } from "./indicators.js";

export const EMA_SLOPE_BARS = 5;
export const EMA_SLOPE_ATR_FRACTION = 0.05;

export const TREND_CLASSES = [
  "bullish",
  "bearish",
  "sideways",
  "transitioning",
  "insufficient_data",
] as const;
export type TrendClass = (typeof TREND_CLASSES)[number];

export type TrendFacts = {
  ema20: number | null;
  ema50: number | null;
  ema200: number | null;
  close: number | null;
  rsi: number | null;
  atr: number | null;
  /** EMA 20 now minus EMA 20 five candles earlier. Null when either value is unavailable. */
  slope: number | null;
};

export type TrendAssessment = {
  classification: TrendClass;
  /** Calculated facts first. The last line states that the class is a heuristic. */
  evidence: readonly string[];
  facts: TrendFacts;
};

export function classifyFromFacts(facts: TrendFacts): TrendAssessment {
  const evidence: string[] = [];
  if (facts.rsi !== null) {
    evidence.push(rsiEvidence(facts.rsi));
  }
  if (
    facts.ema20 === null ||
    facts.ema50 === null ||
    facts.ema200 === null ||
    facts.close === null ||
    facts.atr === null ||
    facts.slope === null
  ) {
    evidence.unshift(
      "EMA 20, EMA 50, EMA 200, ATR 14, and a 5-candle EMA 20 slope are required before a trend class is assigned.",
    );
    return { classification: "insufficient_data", evidence, facts };
  }

  const threshold = facts.atr * EMA_SLOPE_ATR_FRACTION;
  const alignedUp = facts.ema20 > facts.ema50 && facts.ema50 > facts.ema200;
  const alignedDown = facts.ema20 < facts.ema50 && facts.ema50 < facts.ema200;
  const slopeUp = facts.slope > threshold;
  const slopeDown = facts.slope < -threshold;

  if (alignedUp) {
    evidence.unshift("EMA 20 is above EMA 50, and EMA 50 is above EMA 200.");
  } else if (alignedDown) {
    evidence.unshift("EMA 20 is below EMA 50, and EMA 50 is below EMA 200.");
  } else {
    evidence.unshift("EMA 20, EMA 50, and EMA 200 are not stacked in one direction.");
  }

  if (facts.close > facts.ema20) {
    evidence.splice(1, 0, "Close is above EMA 20.");
  } else if (facts.close < facts.ema20) {
    evidence.splice(1, 0, "Close is below EMA 20.");
  } else {
    evidence.splice(1, 0, "Close equals EMA 20.");
  }

  if (slopeUp) {
    evidence.splice(2, 0, "EMA 20 slope over 5 closed candles is up relative to 5% of ATR 14.");
  } else if (slopeDown) {
    evidence.splice(2, 0, "EMA 20 slope over 5 closed candles is down relative to 5% of ATR 14.");
  } else {
    evidence.splice(2, 0, "EMA 20 slope over 5 closed candles is flat relative to 5% of ATR 14.");
  }

  let classification: TrendClass;
  if (alignedUp && facts.close > facts.ema20 && slopeUp) {
    classification = "bullish";
  } else if (alignedDown && facts.close < facts.ema20 && slopeDown) {
    classification = "bearish";
  } else if (!alignedUp && !alignedDown && !slopeUp && !slopeDown) {
    classification = "sideways";
  } else {
    classification = "transitioning";
  }
  evidence.push(
    `Classification ${classification} is a heuristic combining those facts. It is not a forecast.`,
  );
  return { classification, evidence, facts };
}

export function classifyTrend(candles: readonly Candle[]): TrendAssessment {
  const closes = candles.map((candle) => candle.close);
  const ema20 = ema(closes, 20);
  const ema50 = ema(closes, 50);
  const ema200 = ema(closes, 200);
  const rsiValues = rsi(closes, 14);
  const atrValues = atr(candles, 14);
  const last = candles.length - 1;
  const slopeIndex = last - EMA_SLOPE_BARS;
  const latestEma20 = last >= 0 ? (ema20[last] ?? null) : null;
  const earlierEma20 = slopeIndex >= 0 ? (ema20[slopeIndex] ?? null) : null;
  const facts: TrendFacts = {
    ema20: latestEma20,
    ema50: last >= 0 ? (ema50[last] ?? null) : null,
    ema200: last >= 0 ? (ema200[last] ?? null) : null,
    close: last >= 0 ? (candles[last]?.close ?? null) : null,
    rsi: last >= 0 ? (rsiValues[last] ?? null) : null,
    atr: last >= 0 ? (atrValues[last] ?? null) : null,
    slope: latestEma20 !== null && earlierEma20 !== null ? latestEma20 - earlierEma20 : null,
  };
  return classifyFromFacts(facts);
}

export function rsiEvidence(value: number): string {
  const printed = value.toFixed(2);
  if (value >= 70) {
    return `RSI 14 is ${printed}. At or above 70 is an overbought momentum label, not a reversal signal.`;
  }
  if (value <= 30) {
    return `RSI 14 is ${printed}. At or below 30 is an oversold momentum label, not a reversal signal.`;
  }
  return `RSI 14 is ${printed}. It is between 30 and 70 and is not a reversal signal.`;
}
