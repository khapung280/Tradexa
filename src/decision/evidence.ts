import type { InstrumentAnalysis, TimeframeSection } from "../analysis/technical.js";
import { ANALYSIS_LOOKBACK_BARS } from "../analysis/technical.js";
import { formatUtcTimestamp } from "../market/nativeCandles.js";
import { detectSession } from "../market/sessionState.js";
import { classifyFreshness, type FreshnessPolicy } from "./freshness.js";
import type { EvidenceItem, MarketSnapshot, TimeframeSnapshot } from "./record.js";

export const REQUIRED_HISTORY_BARS = 200;
export const STRATEGY_EVIDENCE_UNAVAILABLE =
  "No cached Phase 8 result is attached to this request. A full-history backtest is not run for a decision, and a missing result is not estimated.";

const SPREAD_REASON =
  "No verified live spread and no verified historical variable spread. A Phase 8 constant spread is a simulation setting, not a quote for this decision.";

export function buildSnapshot(
  analysis: InstrumentAnalysis,
  nowMs: number,
  mode: "HISTORICAL" | "LIVE",
  policy: FreshnessPolicy,
): MarketSnapshot {
  if (analysis.kind !== "report") {
    return {
      symbol: analysis.symbol,
      source: "none",
      mode,
      createdAtMs: nowMs,
      timeframes: [],
      spread: { available: false, source: "unavailable", reason: SPREAD_REASON },
      liveSourceVerified: false,
      limitations: [analysis.text],
    };
  }
  return {
    symbol: analysis.symbol,
    source: "Dukascopy native bid CSV, M5 through D1. M1 is not rebuilt into those timeframes. This is not a live feed.",
    mode,
    createdAtMs: nowMs,
    timeframes: analysis.sections.map((section) => timeframeSnapshot(section, nowMs, mode, policy)),
    spread: { available: false, source: "unavailable", reason: SPREAD_REASON },
    liveSourceVerified: false,
    limitations: [
      "The snapshot uses completed historical candles. It is not a live bid or ask.",
      SPREAD_REASON,
    ],
  };
}

export function buildEvidence(analysis: InstrumentAnalysis, nowMs?: number): EvidenceItem[] {
  if (analysis.kind !== "report") {
    return [{
      id: "DATA_UNAVAILABLE",
      polarity: "warning",
      text: analysis.text,
    }];
  }
  const items: EvidenceItem[] = [{
    id: "DATA_HISTORICAL_NOT_LIVE",
    polarity: "warning",
    text: "The candles are historical Dukascopy bids. They are not a live quote.",
  }, {
    id: "SPREAD_UNAVAILABLE",
    polarity: "warning",
    text: SPREAD_REASON,
  }, {
    id: "STRATEGY_PERFORMANCE_UNAVAILABLE",
    polarity: "warning",
    text: STRATEGY_EVIDENCE_UNAVAILABLE,
  }];
  for (const section of analysis.sections) {
    items.push(...sectionEvidence(section));
  }
  if (nowMs !== undefined) {
    items.push(...sessionEvidence(nowMs));
  }
  const bullish = items.filter((item) => item.id.startsWith("TREND_") && item.polarity === "bullish").map((item) => item.id);
  const bearish = items.filter((item) => item.id.startsWith("TREND_") && item.polarity === "bearish").map((item) => item.id);
  if (bullish.length > 0 && bearish.length > 0) {
    items.push({
      id: "MTF_CONFLICT_BULLISH_BEARISH",
      polarity: "warning",
      text: `Trend classes disagree: ${bullish.join(", ")} and ${bearish.join(", ")}.`,
    });
  }
  return items;
}

function timeframeSnapshot(
  section: TimeframeSection,
  nowMs: number,
  mode: "HISTORICAL" | "LIVE",
  policy: FreshnessPolicy,
): TimeframeSnapshot {
  if (section.status !== "ok") {
    return {
      timeframe: section.timeframe,
      latestOpenMs: null,
      latestCloseMs: null,
      closed: false,
      availableCount: 0,
      requiredCount: REQUIRED_HISTORY_BARS,
      completeness: "unavailable",
      gapQuality: "unknown",
      freshness: "unavailable",
      ageMs: null,
      limitations: [section.reason],
    };
  }
  const ageMs = nowMs - section.candleCloseMs;
  const unclassified = section.warnings.some((warning) => /unclassified|unexpected/i.test(warning));
  const limitations = [...section.warnings];
  if (section.candlesUsed < REQUIRED_HISTORY_BARS) {
    limitations.push(`${section.timeframe} has ${section.candlesUsed} closed candles. EMA 200 needs ${REQUIRED_HISTORY_BARS}.`);
  }
  return {
    timeframe: section.timeframe,
    latestOpenMs: section.candleOpenMs,
    latestCloseMs: section.candleCloseMs,
    closed: true,
    availableCount: section.candlesUsed,
    requiredCount: REQUIRED_HISTORY_BARS,
    completeness: section.candlesUsed >= REQUIRED_HISTORY_BARS ? "complete" : "insufficient",
    gapQuality: unclassified ? "unclassified_gap" : "none_observed",
    freshness: classifyFreshness(mode, ageMs, policy, false),
    ageMs,
    limitations,
  };
}

function sessionEvidence(nowMs: number): EvidenceItem[] {
  const items: EvidenceItem[] = [];
  for (const name of ["london", "newyork"] as const) {
    const detected = detectSession(name, nowMs);
    if (!detected.ok) {
      continue;
    }
    items.push({
      id: `SESSION_${name.toUpperCase()}_${detected.state.toUpperCase()}`,
      polarity: "neutral",
      text: `${detected.session.displayName} catalog hours are ${detected.state} at the decision clock. City hours are not a verified market closure.`,
    });
  }
  return items;
}

function sectionEvidence(section: TimeframeSection): EvidenceItem[] {
  if (section.status !== "ok") {
    return [{
      id: `DATA_${section.timeframe}_UNAVAILABLE`,
      polarity: "warning",
      text: section.reason,
    }];
  }
  const tf = section.timeframe;
  const items: EvidenceItem[] = [];
  const indicators = section.indicators;
  if (indicators.ema20 !== null && indicators.ema50 !== null) {
    const above = indicators.ema20 > indicators.ema50;
    items.push({
      id: above ? `EMA_${tf}_20_ABOVE_50` : `EMA_${tf}_20_BELOW_50`,
      polarity: above ? "bullish" : "bearish",
      text: `${tf} EMA 20 is ${above ? "above" : "below"} EMA 50 on the closed candle ending ${formatUtcTimestamp(section.candleCloseMs)}.`,
    });
  }
  if (indicators.ema50 !== null && indicators.ema200 !== null) {
    const above = indicators.ema50 > indicators.ema200;
    items.push({
      id: above ? `EMA_${tf}_50_ABOVE_200` : `EMA_${tf}_50_BELOW_200`,
      polarity: above ? "bullish" : "bearish",
      text: `${tf} EMA 50 is ${above ? "above" : "below"} EMA 200.`,
    });
  }
  if (indicators.rsi14 !== null) {
    if (indicators.rsi14 >= 70) {
      items.push({ id: `RSI_${tf}_OVERBOUGHT`, polarity: "bearish", text: `${tf} RSI 14 is at or above 70.` });
    } else if (indicators.rsi14 <= 30) {
      items.push({ id: `RSI_${tf}_OVERSOLD`, polarity: "bullish", text: `${tf} RSI 14 is at or below 30.` });
    } else {
      items.push({ id: `RSI_${tf}_BETWEEN_30_AND_70`, polarity: "neutral", text: `${tf} RSI 14 is between 30 and 70.` });
    }
  }
  if (indicators.macd !== null && indicators.macdSignal !== null) {
    const above = indicators.macd > indicators.macdSignal;
    items.push({
      id: above ? `MACD_${tf}_ABOVE_SIGNAL` : `MACD_${tf}_BELOW_SIGNAL`,
      polarity: above ? "bullish" : "bearish",
      text: `${tf} MACD line is ${above ? "above" : "below"} its signal line.`,
    });
  }
  if (indicators.macdCrossover === "bullish" || indicators.macdCrossover === "bearish") {
    items.push({
      id: `MACD_${tf}_CROSS_${indicators.macdCrossover.toUpperCase()}`,
      polarity: indicators.macdCrossover,
      text: `${tf} MACD crossover on the latest closed comparison is ${indicators.macdCrossover}.`,
    });
  }
  const trend = section.trend.classification;
  if (trend !== "insufficient_data") {
    items.push({
      id: `TREND_${tf}_${trend.toUpperCase()}`,
      polarity: trend === "bullish" ? "bullish" : trend === "bearish" ? "bearish" : "neutral",
      text: `${tf} trend class is ${trend}. The class is the Phase 7 heuristic, not a forecast.`,
    });
  }
  if (section.structure.state === "bullish" || section.structure.state === "bearish") {
    items.push({
      id: `STRUCTURE_${tf}_${section.structure.state.toUpperCase()}`,
      polarity: section.structure.state,
      text: `${tf} market structure state is ${section.structure.state}.`,
    });
  }
  if (section.structure.lastLowLabel === "LL") {
    items.push({ id: `STRUCTURE_${tf}_LOWER_LOW`, polarity: "bearish", text: `${tf} last confirmed swing low is a lower low.` });
  }
  if (section.structure.lastHighLabel === "HH") {
    items.push({ id: `STRUCTURE_${tf}_HIGHER_HIGH`, polarity: "bullish", text: `${tf} last confirmed swing high is a higher high.` });
  }
  if (section.zones.support.some((zone) => zone.containsPrice)) {
    items.push({ id: `ZONE_${tf}_PRICE_INSIDE_SUPPORT`, polarity: "bullish", text: `${tf} close is inside a calculated support zone.` });
  }
  if (section.zones.resistance.some((zone) => zone.containsPrice)) {
    items.push({ id: `ZONE_${tf}_PRICE_INSIDE_RESISTANCE`, polarity: "bearish", text: `${tf} close is inside a calculated resistance zone.` });
  }
  if (section.candlesUsed < ANALYSIS_LOOKBACK_BARS && section.candlesUsed < REQUIRED_HISTORY_BARS) {
    items.push({
      id: `DATA_${tf}_INSUFFICIENT_HISTORY`,
      polarity: "warning",
      text: `${tf} does not have ${REQUIRED_HISTORY_BARS} closed candles after the latest usable segment.`,
    });
  }
  return items;
}
