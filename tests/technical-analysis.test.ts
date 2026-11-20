import { describe, expect, it } from "vitest";
import type { Candle } from "../src/market/nativeCandles.ts";
import { formatInstrumentAnalysis } from "../src/analysis/formatTechnical.ts";
import { detectStructure } from "../src/analysis/structure.ts";
import type { InstrumentAnalysis } from "../src/analysis/technical.ts";
import { classifyFromFacts, classifyTrend, type TrendFacts } from "../src/analysis/trend.ts";
import { detectZones, ZONE_ATR_TOLERANCE, zoneStrength } from "../src/analysis/zones.ts";
import type { Swing } from "../src/analysis/structure.ts";

function candle(index: number, high: number, low: number, close = (high + low) / 2): Candle {
  return {
    timeMs: Date.UTC(2024, 0, 1) + index * 60_000,
    open: close,
    high,
    low,
    close,
    volume: 1,
  };
}

function swing(kind: "high" | "low", price: number, index: number): Swing {
  return {
    kind,
    index,
    timeMs: index,
    price,
    confirmedAtIndex: index + 2,
    label: null,
  };
}

const readyFacts = {
  rsi: 55,
  atr: 1,
} satisfies Pick<TrendFacts, "rsi" | "atr">;

describe("trend classification", () => {
  it("names bullish, bearish, sideways, transitioning, and insufficient data from explicit facts", () => {
    expect(
      classifyFromFacts({
        ...readyFacts,
        ema20: 3,
        ema50: 2,
        ema200: 1,
        close: 4,
        slope: 1,
      }).classification,
    ).toBe("bullish");
    expect(
      classifyFromFacts({
        ...readyFacts,
        ema20: 1,
        ema50: 2,
        ema200: 3,
        close: 0.5,
        slope: -1,
      }).classification,
    ).toBe("bearish");
    expect(
      classifyFromFacts({
        ...readyFacts,
        ema20: 2,
        ema50: 2.2,
        ema200: 1.5,
        close: 2.1,
        slope: 0,
      }).classification,
    ).toBe("sideways");
    const transitioning = classifyFromFacts({
      ...readyFacts,
      ema20: 3,
      ema50: 2,
      ema200: 1,
      close: 2.5,
      slope: 1,
    });
    expect(transitioning.classification).toBe("transitioning");
    expect(transitioning.evidence.join(" ")).toMatch(/not a forecast/);
    expect(
      classifyFromFacts({
        ema20: 1,
        ema50: 1,
        ema200: null,
        close: 1,
        rsi: null,
        atr: null,
        slope: null,
      }).classification,
    ).toBe("insufficient_data");
  });

  it("treats RSI 70 and RSI 30 as labels, including inside a bullish fact set", () => {
    const high = classifyFromFacts({
      ...readyFacts,
      ema20: 3,
      ema50: 2,
      ema200: 1,
      close: 4,
      slope: 1,
      rsi: 72,
    });
    expect(high.classification).toBe("bullish");
    expect(high.evidence.join(" ")).toMatch(/not a reversal signal/);
    expect(
      classifyFromFacts({
        ...readyFacts,
        ema20: 1,
        ema50: 2,
        ema200: 3,
        close: 0.5,
        slope: -1,
        rsi: 28,
      }).evidence.join(" "),
    ).toMatch(/not a reversal signal/);
  });

  it("classifies a long rising series as bullish and a long falling series as bearish", () => {
    const rising: Candle[] = [];
    const falling: Candle[] = [];
    for (let index = 0; index < 220; index += 1) {
      const up = 1.1 + index * 0.001;
      const down = 1.5 - index * 0.0005;
      rising.push(candle(index, up + 0.0002, up - 0.0002, up));
      falling.push(candle(index, down + 0.0002, down - 0.0002, down));
    }
    expect(classifyTrend(rising).classification).toBe("bullish");
    expect(classifyTrend(falling).classification).toBe("bearish");
    expect(classifyTrend(rising.slice(0, 199)).classification).toBe("insufficient_data");
  });

  it("classifies a flat exact series as sideways", () => {
    const flat = Array.from({ length: 220 }, (_, index) => candle(index, 2.0002, 1.9998, 2));
    expect(classifyTrend(flat).classification).toBe("sideways");
  });
});

describe("market structure", () => {
  const highs = [1, 3, 1, 2, 1, 4, 1, 2, 1];
  const lows = [0.5, 1, 0.2, 1, 0.4, 1, 0.1, 1, 0.3];
  const candles = highs.map((high, index) => candle(index, high, lows[index] ?? high));

  it("labels HH, HL, LH, and LL only after the right-side candle exists", () => {
    const confirmed = detectStructure(candles, 1, 1);
    expect(confirmed.swings.map((item) => `${item.kind}:${item.label ?? "first"}`)).toEqual([
      "high:first",
      "low:first",
      "high:LH",
      "low:HL",
      "high:HH",
      "low:LL",
      "high:LH",
    ]);
    expect(confirmed.state).toBe("bearish");

    const withoutConfirmation = detectStructure(candles.slice(0, 8), 1, 1);
    expect(withoutConfirmation.swings.some((item) => item.index === 7)).toBe(false);
    expect(withoutConfirmation.lastHighLabel).toBe("HH");
    expect(withoutConfirmation.lastLowLabel).toBe("LL");
    expect(withoutConfirmation.state).toBe("transition");
    expect(withoutConfirmation.note).toMatch(/structure transition/);
  });

  it("uses production pivot width 2 and rejects an extreme that only width 1 can see", () => {
    const series = [
      candle(0, 6, 5),
      candle(1, 2, 1),
      candle(2, 5, 4),
      candle(3, 1, 0.5),
      candle(4, 1, 0.4),
    ];
    expect(detectStructure(series, 1, 1).swings.map((item) => item.index)).toContain(2);
    expect(detectStructure(series, 2, 2).swings).toEqual([]);

    const confirmed = [
      candle(0, 1, 0.5),
      candle(1, 2, 1.5),
      candle(2, 4, 3.5),
      candle(3, 2, 1),
      candle(4, 1.5, 0.4),
      candle(5, 3, 2.5),
      candle(6, 6, 5.5),
      candle(7, 2, 1.5),
      candle(8, 1, 0.5),
    ];
    const wide = detectStructure(confirmed, 2, 2);
    const highs = wide.swings.filter((item) => item.kind === "high");
    expect(highs.map((item) => item.index)).toEqual([2, 6]);
    expect(highs.map((item) => item.label)).toEqual([null, "HH"]);
    expect(highs[1]?.confirmedAtIndex).toBe(8);
  });

  it("does not confirm a tied extreme", () => {
    const tied = [candle(0, 1, 0.4), candle(1, 2, 0.5), candle(2, 2, 0.5), candle(3, 1, 0.4)];
    expect(detectStructure(tied, 1, 1).swings).toEqual([]);
  });
});

describe("support and resistance zones", () => {
  it("clusters nearby swing prices within half of ATR and separates support from resistance", () => {
    const zones = detectZones(
      [swing("low", 1, 1), swing("low", 1.0004, 4), swing("high", 1.02, 2), swing("high", 1.0204, 6)],
      1.01,
      0.001,
      "H1",
    );
    expect(zones.tolerance).toBeCloseTo(0.001 * ZONE_ATR_TOLERANCE, 10);
    expect(zones.support).toHaveLength(1);
    expect(zones.support[0]).toMatchObject({
      type: "support",
      reactions: 2,
      strength: "weak",
      timeframe: "H1",
    });
    expect(zones.support[0]?.representative).toBeCloseTo(1.0002, 10);
    expect(zones.resistance).toHaveLength(1);
    expect(zones.resistance[0]?.type).toBe("resistance");
    expect(zones.note).toMatch(/not guaranteed reversal prices/);
  });

  it("does not merge levels that sit farther apart than the tolerance", () => {
    const zones = detectZones(
      [swing("low", 1, 1), swing("low", 1.01, 3)],
      1.02,
      0.001,
      "H1",
    );
    expect(zones.support).toEqual([]);
    expect(zones.emptySupportReason).toMatch(/at least two reactions/);
    expect(zones.emptySupportReason).not.toMatch(/other side/);
  });

  it("keeps swing lows above the close as historical structure, not resistance", () => {
    const zones = detectZones(
      [swing("low", 1.2, 1), swing("low", 1.2002, 4)],
      1,
      0.001,
      "H1",
    );
    expect(zones.support).toEqual([]);
    expect(zones.resistance).toEqual([]);
    expect(zones.historicalSwingLows).toHaveLength(1);
    expect(zones.historicalSwingLows[0]).toMatchObject({
      type: "historical_swing_low",
      swingKind: "low",
      reactions: 2,
    });
    expect(zones.emptySupportReason).toMatch(/other side/);
    expect(zones.emptySupportReason).not.toMatch(/at least two reactions/);
  });

  it("keeps swing highs below the close as historical structure, not support", () => {
    const zones = detectZones(
      [swing("high", 0.8, 1), swing("high", 0.8002, 4)],
      1,
      0.001,
      "H1",
    );
    expect(zones.support).toEqual([]);
    expect(zones.resistance).toEqual([]);
    expect(zones.historicalSwingHighs[0]?.type).toBe("historical_swing_high");
    expect(zones.historicalSwingHighs[0]?.swingKind).toBe("high");
    expect(zones.emptyResistanceReason).toMatch(/other side/);
  });

  it("keeps mixed confirmed zones and former levels on both sides", () => {
    const zones = detectZones(
      [
        swing("low", 1, 1),
        swing("low", 1.0002, 2),
        swing("low", 1.5, 3),
        swing("low", 1.5002, 4),
        swing("high", 2, 5),
        swing("high", 2.0002, 6),
        swing("high", 0.5, 7),
        swing("high", 0.5002, 8),
      ],
      1.2,
      0.001,
      "H4",
    );
    expect(zones.support[0]?.type).toBe("support");
    expect(zones.resistance[0]?.type).toBe("resistance");
    expect(zones.historicalSwingLows[0]?.type).toBe("historical_swing_low");
    expect(zones.historicalSwingHighs[0]?.type).toBe("historical_swing_high");
    expect(zones.support[0]?.reactions).toBe(2);
    expect(zones.resistance[0]?.reactions).toBe(2);
  });

  it("omits clusters beyond the display limit and reports the count", () => {
    const swings = [1, 1.1, 1.2, 1.3].flatMap((price, group) => [
      swing("low", price, group * 2),
      swing("low", price, group * 2 + 1),
    ]);
    const zones = detectZones(swings, 2, 0, "M30");
    expect(zones.support).toHaveLength(3);
    expect(zones.omitted.support).toBe(1);
    expect(zones.support[0]?.representative).toBeCloseTo(1.3, 10);
  });

  it("explains an empty support list when every swing low is above the close", () => {
    const zones = detectZones(
      [swing("low", 1.2, 1), swing("low", 1.2002, 4)],
      1,
      0.001,
      "H1",
    );
    const report: InstrumentAnalysis = {
      kind: "report",
      symbol: "EURUSD",
      asOfMs: Date.UTC(2024, 0, 2),
      summary: ["H1 trend class is insufficient_data."],
      sections: [
        {
          status: "ok",
          timeframe: "H1",
          candleOpenMs: Date.UTC(2024, 0, 1),
          candleCloseMs: Date.UTC(2024, 0, 2),
          close: 1,
          candlesUsed: 10,
          filesRead: 1,
          indicators: {
            ema20: null,
            ema50: null,
            ema200: null,
            emaCrossover: "none",
            rsi14: null,
            macd: null,
            macdSignal: null,
            macdHistogram: null,
            macdCrossover: "none",
            atr14: 0.0005,
            volatility: "unavailable",
          },
          trend: {
            classification: "insufficient_data",
            evidence: [],
            facts: {
              ema20: null,
              ema50: null,
              ema200: null,
              close: 1,
              rsi: null,
              atr: 0.0005,
              slope: null,
            },
          },
          structure: {
            state: "insufficient_data",
            swings: [],
            lastHighLabel: null,
            lastLowLabel: null,
            note: "Not enough swings.",
          },
          zones,
          warnings: [],
        },
      ],
    };
    const text = formatInstrumentAnalysis(report);
    expect(text).toContain("Historical swing lows:");
    expect(text).toContain("not confirmed resistance");
    expect(text).toContain(zones.emptySupportReason);
    expect(text).not.toMatch(/none with at least two clustered reactions/);
  });

  it("does not extend a cluster past the tolerance of its lowest price", () => {
    const zones = detectZones(
      [
        swing("low", 1, 1),
        swing("low", 1.0004, 2),
        swing("low", 1.0008, 3),
      ],
      1.1,
      0.001,
      "M15",
    );
    expect(zones.support).toHaveLength(1);
    expect(zones.support[0]?.reactions).toBe(2);
    expect(zones.support[0]?.upper).toBeCloseTo(1.0004, 10);
  });

  it("ranks four equal reactions as strong", () => {
    expect(zoneStrength(2)).toBe("weak");
    expect(zoneStrength(3)).toBe("moderate");
    expect(zoneStrength(4)).toBe("strong");
    const strong = detectZones(
      [1, 2, 3, 4].map((index) => swing("high", 2, index)),
      1,
      0,
      "M5",
    );
    expect(strong.resistance[0]?.strength).toBe("strong");
  });
});
