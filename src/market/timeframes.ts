export const TIMEFRAME_IDS = ["M1", "M5", "M15", "M30", "H1", "H4", "D1"] as const;
export type TimeframeId = (typeof TIMEFRAME_IDS)[number];

export type Timeframe = {
  id: TimeframeId;
  displayName: string;
  minutes: number;
};

export const TIMEFRAMES: readonly Timeframe[] = [
  { id: "M1", displayName: "1 minute", minutes: 1 },
  { id: "M5", displayName: "5 minutes", minutes: 5 },
  { id: "M15", displayName: "15 minutes", minutes: 15 },
  { id: "M30", displayName: "30 minutes", minutes: 30 },
  { id: "H1", displayName: "60 minutes", minutes: 60 },
  { id: "H4", displayName: "240 minutes", minutes: 240 },
  { id: "D1", displayName: "1440 minutes", minutes: 1440 },
];

const TIMEFRAMES_BY_ID = new Map<string, Timeframe>(
  TIMEFRAMES.map((timeframe) => [timeframe.id, timeframe]),
);

export function normalizeTimeframeId(raw: string): string {
  return raw.trim().toUpperCase();
}

export function findTimeframe(raw: string): Timeframe | undefined {
  const id = normalizeTimeframeId(raw);
  if (id.length === 0) {
    return undefined;
  }
  return TIMEFRAMES_BY_ID.get(id);
}

export function isTimeframeId(value: string): value is TimeframeId {
  return findTimeframe(value) !== undefined;
}
