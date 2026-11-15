import { describe, expect, it } from "vitest";
import {
  TIMEFRAME_IDS,
  TIMEFRAMES,
  findTimeframe,
  isTimeframeId,
  normalizeTimeframeId,
} from "../src/market/timeframes.ts";

const EXPECTED = [
  { id: "M1", displayName: "1 minute", minutes: 1 },
  { id: "M5", displayName: "5 minutes", minutes: 5 },
  { id: "M15", displayName: "15 minutes", minutes: 15 },
  { id: "M30", displayName: "30 minutes", minutes: 30 },
  { id: "H1", displayName: "60 minutes", minutes: 60 },
  { id: "H4", displayName: "240 minutes", minutes: 240 },
  { id: "D1", displayName: "1440 minutes", minutes: 1440 },
] as const;

describe("TIMEFRAMES", () => {
  it("contains exactly the seven supported Forex timeframes", () => {
    expect(TIMEFRAME_IDS).toEqual(["M1", "M5", "M15", "M30", "H1", "H4", "D1"]);
    expect(TIMEFRAMES).toHaveLength(7);
    expect(TIMEFRAMES).toEqual([...EXPECTED]);
  });

  it("looks up every catalog id", () => {
    for (const expected of EXPECTED) {
      expect(findTimeframe(expected.id)).toEqual(expected);
      expect(findTimeframe(expected.id.toLowerCase())).toEqual(expected);
      expect(isTimeframeId(expected.id)).toBe(true);
    }
  });

  it("accepts trimmed mixed-case ids", () => {
    expect(normalizeTimeframeId(" m15 ")).toBe("M15");
    expect(findTimeframe(" h4 ")).toEqual({
      id: "H4",
      displayName: "240 minutes",
      minutes: 240,
    });
    expect(findTimeframe("d1")).toEqual({
      id: "D1",
      displayName: "1440 minutes",
      minutes: 1440,
    });
  });

  it("rejects unsupported or invalid timeframe values", () => {
    const invalid = ["", "   ", "M2", "H2", "W1", "1", "60", "daily", "M1M5"];
    for (const value of invalid) {
      expect(findTimeframe(value), value).toBeUndefined();
      expect(isTimeframeId(value), value).toBe(false);
    }
  });
});
