import { formatUtcTimestamp } from "../market/nativeCandles.js";
import type { DecisionRecord } from "./record.js";

export function formatDecision(record: DecisionRecord): string {
  const latest = record.snapshot.timeframes
    .filter((item) => item.latestCloseMs !== null)
    .map((item) => `${item.timeframe} ${formatUtcTimestamp(item.latestCloseMs ?? 0)} (${item.freshness}, ${item.completeness})`)
    .join("\n");
  const bullish = record.research.bullish.slice(0, 6);
  const bearish = record.research.bearish.slice(0, 6);
  const lines = [
    `Tradexa decision: ${record.symbol}`,
    record.finalLabel,
    record.research.label,
    `Research classification: ${record.research.classification}. ${record.research.reason}`,
    "This assessment does not send an order. It is not a forecast and it is not future performance.",
    `Data mode: ${record.snapshot.mode}`,
    `Operating mode: ${record.operatingMode}`,
    `Snapshot time: ${formatUtcTimestamp(record.snapshot.createdAtMs)}`,
    latest.length > 0 ? `Latest closed candles:\n${latest}` : "Latest closed candle: unavailable",
    `Live quote: ${record.liveQuote.status}. ${record.liveQuote.detail}`,
    `Spread: ${record.liveQuote.spreadPips === null ? "unavailable" : `${record.liveQuote.spreadPips} pips from verified bid/ask`}. ${record.snapshot.spread.reason}`,
    `Bullish evidence: ${bullish.length > 0 ? bullish.join(" ") : "none"}`,
    `Bearish evidence: ${bearish.length > 0 ? bearish.join(" ") : "none"}`,
    `Conflicts: ${record.research.conflicts.length > 0 ? record.research.conflicts.join(" ") : "none recorded"}`,
    `Risk gate: ${record.risk.status}. ${record.risk.detail}`,
    "Account equity, live exposure, and broker P/L are NOT VERIFIED. Reported losses are user-entered.",
    `Entry, stop, target, and position size: ${record.levels.entry}`,
    `AI: ${aiLine(record)}`,
    `WAIT reasons: ${record.waitReasons.join(" ")}`,
    `Strategy evidence: ${strategyLine(record)}`,
    `Trade levels: ${record.quotePlan.reason}`,
    `Decision id: ${record.id}`,
  ];
  return lines.join("\n");
}

export function formatDecisionHistory(records: readonly DecisionRecord[]): string {
  if (records.length === 0) {
    return "No stored decisions for this chat. A decision is a shadow-mode assessment, not an order.";
  }
  const lines = ["Stored shadow-mode decisions. These are not orders and are not labelled wins or losses.", ""];
  for (const record of records) {
    lines.push(
      `${formatUtcTimestamp(record.createdAtMs)} ${record.symbol}: ${record.finalLabel} Research ${record.research.classification}. Id ${record.id}.`,
    );
  }
  return lines.join("\n");
}

function strategyLine(record: DecisionRecord): string {
  if (!record.strategyEvidence.available) {
    return record.strategyEvidence.reason;
  }
  return `${record.strategyEvidence.references.length} historical Phase 8 rows for ${record.strategyEvidence.symbol}. ${record.strategyEvidence.disclaimer}`;
}

function aiLine(record: DecisionRecord): string {
  if (record.ai.status === "accepted") {
    return `${record.ai.assessment.assessmentSummary} Qualitative wording is not a win probability.`;
  }
  return record.ai.reason;
}
