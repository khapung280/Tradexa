import { CHART_READING_NOTES } from "./guide.js";
import type { AnalysisReport } from "./types.js";

export const ANALYSIS_DISCLAIMER =
  "This is an empty analysis template. Tradexa does not generate trading signals or personalized financial advice.";

export function createUnavailableReport(): AnalysisReport {
  return {
    dataStatus: "unavailable",
    trend: null,
    support: [],
    resistance: [],
    notes: CHART_READING_NOTES,
    disclaimer: ANALYSIS_DISCLAIMER,
  };
}

export function formatAnalysisReport(report: AnalysisReport): string {
  const trend =
    report.trend === null ? "not available (no live data)" : report.trend;
  const support =
    report.support.length === 0 ? "not available (no live data)" : report.support.join(", ");
  const resistance =
    report.resistance.length === 0
      ? "not available (no live data)"
      : report.resistance.join(", ");

  const header = ["Tradexa analysis", ""];
  if (report.pair !== undefined) {
    header.push(`Pair: ${report.pair}`);
  }
  if (report.timeframe !== undefined) {
    header.push(`Timeframe: ${report.timeframe}`);
  }

  return [
    ...header,
    `Data: ${report.dataStatus}`,
    `Trend: ${trend}`,
    `Support: ${support}`,
    `Resistance: ${resistance}`,
    "",
    "Notes:",
    ...report.notes.map((note) => `- ${note}`),
    "",
    report.disclaimer,
  ].join("\n");
}
