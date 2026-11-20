/** Age after which a closed candle cannot support a live assessment. Historical research stays available and non-actionable. */
export const DEFAULT_LIVE_MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000;

export type FreshnessPolicy = {
  liveMaxAgeMs: number;
};

export const DEFAULT_FRESHNESS_POLICY: FreshnessPolicy = {
  liveMaxAgeMs: DEFAULT_LIVE_MAX_AGE_MS,
};

export function candleAgeMs(nowMs: number, latestCloseMs: number | null): number | null {
  if (latestCloseMs === null || !Number.isFinite(nowMs) || !Number.isFinite(latestCloseMs)) {
    return null;
  }
  return nowMs - latestCloseMs;
}

export function classifyFreshness(
  mode: "HISTORICAL" | "LIVE",
  ageMs: number | null,
  policy: FreshnessPolicy = DEFAULT_FRESHNESS_POLICY,
  liveSourceVerified = false,
): "historical" | "fresh" | "stale" | "unavailable" {
  if (ageMs === null) {
    return "unavailable";
  }
  if (mode === "HISTORICAL") {
    return "historical";
  }
  if (!liveSourceVerified) {
    return "stale";
  }
  return ageMs >= 0 && ageMs <= policy.liveMaxAgeMs ? "fresh" : "stale";
}
