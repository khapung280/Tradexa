import type { NativeAnalysisTimeframe } from "../market/nativeCandles.js";
import type { Swing } from "./structure.js";

/** A swing joins a cluster only when it is within this fraction of ATR of the cluster's lowest price. */
export const ZONE_ATR_TOLERANCE = 0.5;
export const ZONE_MIN_REACTIONS = 2;
export const MAX_ZONES_PER_SIDE = 3;

export const ZONE_STRENGTHS = ["weak", "moderate", "strong"] as const;
export type ZoneStrength = (typeof ZONE_STRENGTHS)[number];

export const ZONE_ROLES = [
  "support",
  "resistance",
  "historical_swing_low",
  "historical_swing_high",
] as const;
export type ZoneRole = (typeof ZONE_ROLES)[number];

export type PriceZone = {
  type: ZoneRole;
  swingKind: "high" | "low";
  lower: number;
  upper: number;
  representative: number;
  reactions: number;
  strength: ZoneStrength;
  timeframe: NativeAnalysisTimeframe;
  containsPrice: boolean;
};

export type ZoneSet = {
  support: readonly PriceZone[];
  resistance: readonly PriceZone[];
  historicalSwingLows: readonly PriceZone[];
  historicalSwingHighs: readonly PriceZone[];
  omitted: {
    support: number;
    resistance: number;
    historicalSwingLows: number;
    historicalSwingHighs: number;
  };
  tolerance: number | null;
  emptySupportReason: string;
  emptyResistanceReason: string;
  note: string;
};

/**
 * Clusters confirmed swing prices. Support is a cluster whose representative
 * price is below the latest close. Resistance is above it. Two reactions are
 * weak, three are moderate, and four or more are strong.
 */
export function detectZones(
  swings: readonly Swing[],
  lastClose: number | null,
  atrValue: number | null,
  timeframe: NativeAnalysisTimeframe,
): ZoneSet {
  if (lastClose === null || !Number.isFinite(lastClose)) {
    return {
      support: [],
      resistance: [],
      historicalSwingLows: [],
      historicalSwingHighs: [],
      omitted: { support: 0, resistance: 0, historicalSwingLows: 0, historicalSwingHighs: 0 },
      tolerance: null,
      emptySupportReason: "No closed price is available for support and resistance.",
      emptyResistanceReason: "No closed price is available for support and resistance.",
      note: "No closed price is available for support and resistance.",
    };
  }
  const tolerance = atrValue !== null && Number.isFinite(atrValue) && atrValue > 0
    ? atrValue * ZONE_ATR_TOLERANCE
    : 0;
  const lowPrices = swings.filter((swing) => swing.kind === "low").map((swing) => swing.price);
  const highPrices = swings.filter((swing) => swing.kind === "high").map((swing) => swing.price);
  const lowZones = zonesFromSwings(lowPrices, "low", lastClose, tolerance, timeframe);
  const highZones = zonesFromSwings(highPrices, "high", lastClose, tolerance, timeframe);
  const support = limitZones(nearest(lowZones.filter((zone) => zone.type === "support"), lastClose));
  const historicalSwingLows = limitZones(
    nearest(lowZones.filter((zone) => zone.type === "historical_swing_low"), lastClose),
  );
  const resistance = limitZones(nearest(highZones.filter((zone) => zone.type === "resistance"), lastClose));
  const historicalSwingHighs = limitZones(
    nearest(highZones.filter((zone) => zone.type === "historical_swing_high"), lastClose),
  );
  return {
    support: support.kept,
    resistance: resistance.kept,
    historicalSwingLows: historicalSwingLows.kept,
    historicalSwingHighs: historicalSwingHighs.kept,
    omitted: {
      support: support.omitted,
      resistance: resistance.omitted,
      historicalSwingLows: historicalSwingLows.omitted,
      historicalSwingHighs: historicalSwingHighs.omitted,
    },
    tolerance,
    emptySupportReason: emptySideReason("low", lowPrices, lowZones, lastClose),
    emptyResistanceReason: emptySideReason("high", highPrices, highZones, lastClose),
    note:
      "Support is a swing-low cluster below the close. Resistance is a swing-high cluster above the close. A swing low at or above the close stays a historical swing low and is not relabeled as resistance. A swing high at or below the close stays a historical swing high and is not relabeled as support. Zones are not guaranteed reversal prices.",
  };
}

function nearest(zones: readonly PriceZone[], lastClose: number): PriceZone[] {
  return [...zones].sort(
    (left, right) =>
      Math.abs(left.representative - lastClose) - Math.abs(right.representative - lastClose),
  );
}

function limitZones(zones: readonly PriceZone[]): { kept: PriceZone[]; omitted: number } {
  return {
    kept: zones.slice(0, MAX_ZONES_PER_SIDE),
    omitted: Math.max(0, zones.length - MAX_ZONES_PER_SIDE),
  };
}

function emptySideReason(
  kind: "high" | "low",
  prices: readonly number[],
  zones: readonly PriceZone[],
  lastClose: number,
): string {
  const confirmed = kind === "low" ? "support" : "resistance";
  const historical = kind === "low" ? "historical swing lows" : "historical swing highs";
  const below = kind === "low";
  if (prices.length === 0) {
    return kind === "low"
      ? "No confirmed swing lows are in this window."
      : "No confirmed swing highs are in this window.";
  }
  const placed = zones.filter((zone) => zone.type === confirmed);
  if (placed.length > 0) {
    return "";
  }
  const historicalCount = zones.filter((zone) => zone.type !== confirmed).length;
  const onConfirmedSide = prices.filter((price) => (below ? price < lastClose : price > lastClose)).length;
  const sentences: string[] = [];
  if (historicalCount > 0) {
    sentences.push(
      `Confirmed ${kind === "low" ? "swing-low" : "swing-high"} clusters are on the other side of the latest close. They are listed as ${historical}, not as ${confirmed}.`,
    );
  }
  if (onConfirmedSide > 0) {
    sentences.push(
      `${kind === "low" ? "Swing lows below" : "Swing highs above"} the close do not form a cluster of at least two reactions.`,
    );
  }
  if (sentences.length === 0) {
    sentences.push(`No ${confirmed} cluster qualifies in this window.`);
  }
  return sentences.join(" ");
}

export function zoneStrength(reactions: number): ZoneStrength {
  if (reactions >= 4) {
    return "strong";
  }
  if (reactions >= 3) {
    return "moderate";
  }
  return "weak";
}

function zonesFromSwings(
  prices: readonly number[],
  kind: "high" | "low",
  lastClose: number,
  tolerance: number,
  timeframe: NativeAnalysisTimeframe,
): PriceZone[] {
  const clusters = clusterPrices(prices, tolerance);
  const zones: PriceZone[] = [];
  for (const cluster of clusters) {
    if (cluster.length < ZONE_MIN_REACTIONS) {
      continue;
    }
    const lowest = cluster[0];
    const highest = cluster[cluster.length - 1];
    if (lowest === undefined || highest === undefined) {
      continue;
    }
    const representative = cluster.reduce((sum, price) => sum + price, 0) / cluster.length;
    const widened = lowest === highest && tolerance > 0;
    const lower = widened ? lowest - tolerance / 2 : lowest;
    const upper = widened ? highest + tolerance / 2 : highest;
    const type = zoneRole(kind, representative, lastClose);
    zones.push({
      type,
      swingKind: kind,
      lower,
      upper,
      representative,
      reactions: cluster.length,
      strength: zoneStrength(cluster.length),
      timeframe,
      containsPrice: lastClose >= lower && lastClose <= upper,
    });
  }

  zones.sort((left, right) =>
    kind === "low"
      ? right.representative - left.representative
      : left.representative - right.representative,
  );
  return zones;
}

function zoneRole(kind: "high" | "low", representative: number, lastClose: number): ZoneRole {
  if (kind === "low") {
    return representative < lastClose ? "support" : "historical_swing_low";
  }
  return representative > lastClose ? "resistance" : "historical_swing_high";
}

function clusterPrices(prices: readonly number[], tolerance: number): number[][] {
  const sorted = [...prices].sort((left, right) => left - right);
  const clusters: number[][] = [];
  for (const price of sorted) {
    const current = clusters[clusters.length - 1];
    const anchor = current?.[0];
    if (current === undefined || anchor === undefined || price - anchor > tolerance) {
      clusters.push([price]);
      continue;
    }
    current.push(price);
  }
  return clusters;
}
