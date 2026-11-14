export const DATA_STATUSES = ["unavailable", "stale", "ok"] as const;
export type DataStatus = (typeof DATA_STATUSES)[number];

export const TREND_LABELS = ["up", "down", "range", "unclear"] as const;
export type TrendLabel = (typeof TREND_LABELS)[number];

/**
 * Structured market-analysis template for later data integration.
 * Until a real feed exists, `dataStatus` is `"unavailable"` and
 * trend/levels stay empty. Do not fill this with invented quotes.
 */
export type AnalysisReport = {
  dataStatus: DataStatus;
  trend: TrendLabel | null;
  support: readonly string[];
  resistance: readonly string[];
  notes: readonly string[];
  disclaimer: string;
  pair?: string;
  timeframe?: string;
};
