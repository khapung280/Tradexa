import type { Candle } from "../market/nativeCandles.js";

export const DEFAULT_PIVOT_LEFT = 2;
export const DEFAULT_PIVOT_RIGHT = 2;

export const SWING_LABELS = ["HH", "HL", "LH", "LL", "EH", "EL"] as const;
export type SwingLabel = (typeof SWING_LABELS)[number];

export type Swing = {
  kind: "high" | "low";
  index: number;
  timeMs: number;
  price: number;
  /** First index at which the right-side confirmation candles exist. */
  confirmedAtIndex: number;
  label: SwingLabel | null;
};

export const STRUCTURE_STATES = [
  "bullish",
  "bearish",
  "transition",
  "insufficient_data",
] as const;
export type StructureState = (typeof STRUCTURE_STATES)[number];

export type StructureResult = {
  state: StructureState;
  swings: readonly Swing[];
  lastHighLabel: SwingLabel | null;
  lastLowLabel: SwingLabel | null;
  note: string;
};

/**
 * A swing is confirmed only when `right` candles after it are already in the
 * series. Callers must pass only candles that had closed at the analysis time.
 */
export function detectStructure(
  candles: readonly Candle[],
  left = DEFAULT_PIVOT_LEFT,
  right = DEFAULT_PIVOT_RIGHT,
): StructureResult {
  if (!Number.isInteger(left) || !Number.isInteger(right) || left < 1 || right < 1) {
    throw new Error("Pivot confirmation widths must be positive integers.");
  }

  const swings: Swing[] = [];
  let previousHigh: number | null = null;
  let previousLow: number | null = null;

  for (let index = left; index <= candles.length - 1 - right; index += 1) {
    const candle = candles[index];
    if (candle === undefined) {
      continue;
    }
    if (isStrictExtreme(candles, index, left, right, "high")) {
      const label = labelExtreme(candle.high, previousHigh, "HH", "LH", "EH");
      previousHigh = candle.high;
      swings.push({
        kind: "high",
        index,
        timeMs: candle.timeMs,
        price: candle.high,
        confirmedAtIndex: index + right,
        label,
      });
    }
    if (isStrictExtreme(candles, index, left, right, "low")) {
      const label = labelExtreme(candle.low, previousLow, "HL", "LL", "EL");
      previousLow = candle.low;
      swings.push({
        kind: "low",
        index,
        timeMs: candle.timeMs,
        price: candle.low,
        confirmedAtIndex: index + right,
        label,
      });
    }
  }

  const lastHigh = findLast(swings, "high");
  const lastLow = findLast(swings, "low");
  const lastHighLabel = lastHigh?.label ?? null;
  const lastLowLabel = lastLow?.label ?? null;
  const state = structureState(lastHighLabel, lastLowLabel);
  return {
    state,
    swings,
    lastHighLabel,
    lastLowLabel,
    note: structureNote(state, lastHighLabel, lastLowLabel),
  };
}

function isStrictExtreme(
  candles: readonly Candle[],
  index: number,
  left: number,
  right: number,
  kind: "high" | "low",
): boolean {
  const center = candles[index];
  if (center === undefined) {
    return false;
  }
  const centerPrice = kind === "high" ? center.high : center.low;
  for (let neighbor = index - left; neighbor <= index + right; neighbor += 1) {
    if (neighbor === index) {
      continue;
    }
    const other = candles[neighbor];
    if (other === undefined) {
      return false;
    }
    const otherPrice = kind === "high" ? other.high : other.low;
    const isExtreme = kind === "high" ? centerPrice > otherPrice : centerPrice < otherPrice;
    if (!isExtreme) {
      return false;
    }
  }
  return true;
}

function labelExtreme(
  price: number,
  previous: number | null,
  above: SwingLabel,
  below: SwingLabel,
  equal: SwingLabel,
): SwingLabel | null {
  if (previous === null) {
    return null;
  }
  if (price > previous) {
    return above;
  }
  if (price < previous) {
    return below;
  }
  return equal;
}

function findLast(swings: readonly Swing[], kind: "high" | "low"): Swing | undefined {
  for (let index = swings.length - 1; index >= 0; index -= 1) {
    const swing = swings[index];
    if (swing !== undefined && swing.kind === kind) {
      return swing;
    }
  }
  return undefined;
}

function structureState(
  highLabel: SwingLabel | null,
  lowLabel: SwingLabel | null,
): StructureState {
  if (highLabel === "HH" && lowLabel === "HL") {
    return "bullish";
  }
  if (highLabel === "LH" && lowLabel === "LL") {
    return "bearish";
  }
  if (highLabel === null || lowLabel === null) {
    return "insufficient_data";
  }
  return "transition";
}

function structureNote(
  state: StructureState,
  highLabel: SwingLabel | null,
  lowLabel: SwingLabel | null,
): string {
  if (state === "bullish") {
    return "The latest confirmed swing high is HH and the latest confirmed swing low is HL.";
  }
  if (state === "bearish") {
    return "The latest confirmed swing high is LH and the latest confirmed swing low is LL.";
  }
  if (state === "transition") {
    return `The latest confirmed labels are ${highLabel ?? "none"} and ${lowLabel ?? "none"}. They do not form one HH/HL or LH/LL sequence, so this is a possible structure transition, not a prediction.`;
  }
  return "Confirmed swings are not enough to label both the latest high and the latest low. Equal extremes are not counted as HH, HL, LH, or LL.";
}
