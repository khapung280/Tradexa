import { describe, expect, it } from "vitest";
import {
  atr,
  crossoverFromDiffs,
  ema,
  emaCrossover,
  macd,
  macdCrossover,
  rsi,
  rsiFromAverages,
  trueRanges,
} from "../src/analysis/indicators.ts";

describe("EMA", () => {
  it("seeds with an SMA and then uses k = 2/(period+1)", () => {
    // Period 3. Seed = (10+11+12)/3 = 11. k = 0.5.
    // Next = (13-11)*0.5+11 = 12. Next = (14-12)*0.5+12 = 13.
    const values = ema([10, 11, 12, 13, 14], 3);
    expect(values[0]).toBeNull();
    expect(values[1]).toBeNull();
    expect(values[2]).toBeCloseTo(11, 10);
    expect(values[3]).toBeCloseTo(12, 10);
    expect(values[4]).toBeCloseTo(13, 10);
  });

  it("stays null when history is shorter than the period", () => {
    expect(ema([1, 2, 3, 4], 20)).toEqual([null, null, null, null]);
  });

  it("does not change an earlier value when a later close is appended", () => {
    const prefix = ema([10, 11, 12, 13], 3);
    const full = ema([10, 11, 12, 13, 20], 3);
    expect(prefix[2]).toBe(full[2]);
    expect(prefix[3]).toBe(full[3]);
  });

  it("rejects a non-finite close", () => {
    expect(() => ema([1, Number.NaN, 3], 2)).toThrow(/finite/);
  });
});

describe("RSI Wilder", () => {
  it("uses the mean of the first changes and the Wilder update after that", () => {
    // Changes over [10,11,12,11,11]: +1, +1, -1, 0.
    // First averages use +1, +1, -1. Gain 2/3, loss 1/3, RS = 2.
    // RSI = 100 - 100/3.
    // Next change is 0, so both averages are multiplied by 2/3. RS stays 2.
    const values = rsi([10, 11, 12, 11, 11], 3);
    expect(values.slice(0, 3)).toEqual([null, null, null]);
    expect(values[3]).toBeCloseTo(100 - 100 / 3, 10);
    expect(values[4]).toBeCloseTo(100 - 100 / 3, 10);
  });

  it("returns 100 when every change is a gain", () => {
    expect(rsi([1, 2, 3, 4], 3)[3]).toBe(100);
  });

  it("returns 50 when the market is flat", () => {
    const values = rsi([5, 5, 5, 5], 3);
    expect(values[3]).toBe(50);
    expect(rsiFromAverages(0, 0)).toBe(50);
  });

  it("returns 100 when average loss is zero and average gain is positive", () => {
    expect(rsiFromAverages(1, 0)).toBe(100);
  });

  it("has no value before the first completed average", () => {
    expect(rsi([1, 2, 3, 4], 14).every((value) => value === null)).toBe(true);
  });
});

describe("MACD", () => {
  it("calculates the line, signal, and histogram from the documented EMAs", () => {
    // Fast 3 and slow 5 on 1..10 become parallel after the slow seed.
    // EMA3 from index 2: 2,3,4,5,6,7,8,9
    // EMA5 from index 4: 3,4,5,6,7,8
    // MACD from index 4 is 1. Signal period 3 seeds at the third MACD value.
    const points = macd([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 3, 5, 3);
    expect(points[3]?.macd).toBeNull();
    expect(points[4]?.macd).toBeCloseTo(1, 10);
    expect(points[4]?.signal).toBeNull();
    expect(points[6]?.macd).toBeCloseTo(1, 10);
    expect(points[6]?.signal).toBeCloseTo(1, 10);
    expect(points[6]?.histogram).toBeCloseTo(0, 10);
    expect(points[9]?.histogram).toBeCloseTo(0, 10);
  });

  it("leaves the signal empty during its warm-up", () => {
    const points = macd([1, 2, 3, 4, 5], 3, 5, 3);
    expect(points.every((point) => point.signal === null)).toBe(true);
  });

  it("reads a crossover from the last two completed MACD values", () => {
    expect(crossoverFromDiffs(-0.2, 0.1)).toBe("bullish");
    expect(crossoverFromDiffs(0.2, -0.1)).toBe("bearish");
    expect(crossoverFromDiffs(0.2, 0.1)).toBe("none");
    expect(
      macdCrossover([
        { macd: 1, signal: 2, histogram: -1 },
        { macd: 2, signal: 1, histogram: 1 },
      ]),
    ).toBe("bullish");
  });
});

describe("ATR", () => {
  it("uses the previous close in true range and Wilder smoothing after the SMA seed", () => {
    const bars = [
      { high: 10, low: 8, close: 9 },
      { high: 12, low: 9, close: 11 },
      { high: 11, low: 7, close: 8 },
    ];
    expect(trueRanges(bars)).toEqual([2, 3, 4]);
    // Seed = (2+3)/2 = 2.5. Next = (2.5 * 1 + 4) / 2 = 3.25.
    const values = atr(bars, 2);
    expect(values[0]).toBeNull();
    expect(values[1]).toBeCloseTo(2.5, 10);
    expect(values[2]).toBeCloseTo(3.25, 10);
  });

  it("stays null during warm-up and is zero in a flat market", () => {
    expect(atr([{ high: 1, low: 1, close: 1 }], 14)[0]).toBeNull();
    const flat = Array.from({ length: 14 }, () => ({ high: 2, low: 2, close: 2 }));
    expect(atr(flat, 14)[13]).toBe(0);
  });

  it("rejects an invalid candle", () => {
    expect(() => atr([{ high: 1, low: 2, close: 1.5 }], 1)).toThrow(/invalid/);
  });

  it("does not let a later candle change an earlier true range", () => {
    const first = trueRanges([
      { high: 10, low: 8, close: 9 },
      { high: 12, low: 9, close: 11 },
    ]);
    const extended = trueRanges([
      { high: 10, low: 8, close: 9 },
      { high: 12, low: 9, close: 11 },
      { high: 20, low: 1, close: 15 },
    ]);
    expect(extended[0]).toBe(first[0]);
    expect(extended[1]).toBe(first[1]);
  });
});

describe("EMA crossover", () => {
  it("uses only the last two candles where both averages exist", () => {
    expect(emaCrossover([null, 1, 3], [null, 2, 2])).toBe("bullish");
    expect(emaCrossover([null, 3, 1], [null, 2, 2])).toBe("bearish");
  });
});
