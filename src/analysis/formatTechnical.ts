import { formatUtcTimestamp } from "../market/nativeCandles.js";
import type { Crossover } from "./indicators.js";
import type { Swing } from "./structure.js";
import type { InstrumentAnalysis, TimeframeSection } from "./technical.js";
import type { PriceZone } from "./zones.js";

export const TECHNICAL_DISCLAIMER =
  "This report describes completed historical candles. It is not a prediction, a buy or sell instruction, or a guarantee that price will reverse at any zone or indicator level.";

export function formatInstrumentAnalysis(analysis: InstrumentAnalysis): string {
  if (analysis.kind !== "report") {
    return analysis.text;
  }

  const lines = [
    `Tradexa technical report: ${analysis.symbol}`,
    `Analysis timestamp: ${formatUtcTimestamp(analysis.asOfMs)}`,
    "Source: native Dukascopy historical candles. This is not a live quote.",
    "Only candles whose close time is at or before the analysis timestamp are included.",
    "",
    "Multi-timeframe summary:",
    ...analysis.summary.map((line) => `- ${line}`),
    "",
  ];

  for (const section of analysis.sections) {
    lines.push(...formatSection(section), "");
  }
  lines.push(TECHNICAL_DISCLAIMER);
  return lines.join("\n").trimEnd();
}

function formatSection(section: TimeframeSection): string[] {
  if (section.status === "unavailable") {
    return [`${section.timeframe}`, `Data: unavailable. ${section.reason}`];
  }

  const indicators = section.indicators;
  const lines = [
    section.timeframe,
    `Last closed candle: ${formatUtcTimestamp(section.candleOpenMs)} (closes ${formatUtcTimestamp(section.candleCloseMs)})`,
    `Close: ${formatPrice(section.close)}`,
    `Closed candles used: ${section.candlesUsed}`,
    `EMA 20/50/200: ${formatMaybe(indicators.ema20)} / ${formatMaybe(indicators.ema50)} / ${formatMaybe(indicators.ema200)}`,
    `EMA 20/50 crossover: ${crossoverText(indicators.emaCrossover, "EMA 20", "EMA 50")}`,
    `RSI 14: ${rsiText(section)}`,
    `MACD 12/26/9: line ${formatMaybe(indicators.macd)}, signal ${formatMaybe(indicators.macdSignal)}, histogram ${formatMaybe(indicators.macdHistogram)}`,
    `MACD crossover: ${crossoverText(indicators.macdCrossover, "MACD line", "the signal line")}`,
    `ATR 14: ${formatMaybe(indicators.atr14)} (${volatilityText(indicators.volatility)})`,
    `Trend: ${section.trend.classification}`,
    "Trend evidence:",
    ...section.trend.evidence.map((line) => `- ${line}`),
    `Market structure: ${section.structure.state}. ${section.structure.note}`,
    ...formatSwings(section.structure.swings),
    ...formatZoneBlock(
      "Support:",
      section.zones.support,
      section.zones.emptySupportReason,
      section.zones.omitted.support,
    ),
    ...formatZoneBlock(
      "Resistance:",
      section.zones.resistance,
      section.zones.emptyResistanceReason,
      section.zones.omitted.resistance,
    ),
    ...formatZoneBlock(
      "Historical swing lows:",
      section.zones.historicalSwingLows,
      "No swing-low cluster sits at or above the latest close.",
      section.zones.omitted.historicalSwingLows,
    ),
    ...formatZoneBlock(
      "Historical swing highs:",
      section.zones.historicalSwingHighs,
      "No swing-high cluster sits at or below the latest close.",
      section.zones.omitted.historicalSwingHighs,
    ),
    section.zones.note,
  ];
  if (section.warnings.length > 0) {
    lines.push("Data notes:");
    for (const warning of section.warnings) {
      lines.push(`- ${warning}`);
    }
  }
  return lines;
}

function rsiText(section: Extract<TimeframeSection, { status: "ok" }>): string {
  const sentence = section.trend.evidence.find((line) => line.startsWith("RSI 14"));
  if (sentence !== undefined) {
    return sentence;
  }
  return section.indicators.rsi14 === null ? "warming up" : section.indicators.rsi14.toFixed(2);
}

function formatSwings(swings: readonly Swing[]): string[] {
  const labeled = swings.filter((swing) => swing.label !== null).slice(-6);
  if (labeled.length === 0) {
    return ["Recent confirmed labels: none yet."];
  }
  return [
    `Recent confirmed labels: ${labeled
      .map((swing) => `${swing.label} ${swing.kind} ${formatPrice(swing.price)} at ${formatUtcTimestamp(swing.timeMs)}`)
      .join("; ")}`,
  ];
}

function formatZoneBlock(
  title: string,
  zones: readonly PriceZone[],
  emptyReason: string,
  omitted: number,
): string[] {
  if (zones.length === 0) {
    return [title, `- ${emptyReason}`];
  }
  const lines = [title, ...zones.map((zone) => formatZone(zone))];
  if (omitted > 0) {
    lines.push(`- ${omitted} more clusters in this group are omitted.`);
  }
  return lines;
}

function formatZone(zone: PriceZone): string {
  const inside = zone.containsPrice ? " The latest close is inside the zone." : "";
  const relation =
    zone.type === "historical_swing_low"
      ? " Original swing type: low. This is overhead historical structure, not confirmed resistance."
      : zone.type === "historical_swing_high"
        ? " Original swing type: high. This is underfoot historical structure, not confirmed support."
        : ` Original swing type: ${zone.swingKind}.`;
  return `- ${formatPrice(zone.lower)} to ${formatPrice(zone.upper)}, representative ${formatPrice(zone.representative)}, ${zone.reactions} reactions, ${zone.strength}.${relation}${inside}`;
}

function crossoverText(crossover: Crossover, fast: string, slow: string): string {
  if (crossover === "bullish") {
    return `${fast} crossed above ${slow} on the last two completed values. That is not an entry.`;
  }
  if (crossover === "bearish") {
    return `${fast} crossed below ${slow} on the last two completed values. That is not an entry.`;
  }
  return `No crossover on the last two completed values. A crossover would describe those candles, not an entry.`;
}

function volatilityText(label: "elevated" | "typical" | "quiet" | "unavailable"): string {
  if (label === "unavailable") {
    return "volatility comparison is warming up; ATR is not a direction";
  }
  return `volatility ${label} versus the median of the last 20 ATR values; ATR is not a direction`;
}

function formatMaybe(value: number | null): string {
  return value === null ? "warming up" : formatPrice(value);
}

function formatPrice(value: number): string {
  return value.toFixed(5);
}
