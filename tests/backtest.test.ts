import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { parseUtcDate } from "../src/backtest/dates.ts";
import { simulate } from "../src/backtest/engine.ts";
import { formatBacktestReport } from "../src/backtest/format.ts";
import { loadClosedCandles } from "../src/backtest/load.ts";
import { calculateMetrics } from "../src/backtest/metrics.ts";
import { runBacktest } from "../src/backtest/run.ts";
import { pipValuePerLot, positionLots, resolveQuoteRate } from "../src/backtest/sizing.ts";
import { findStrategy, listStrategies } from "../src/backtest/strategies.ts";
import type { BacktestCandle, ClosedTrade, CurvePoint, Decision, SimulationSettings, Strategy, StrategyBar } from "../src/backtest/types.ts";
import { walkForward } from "../src/backtest/walk.ts";
import { buildBacktestReplies } from "../src/commands/backtest.ts";
import { renderHelpReply } from "../src/commands/help.ts";
import { renderStartReply } from "../src/commands/start.ts";
import { findPair } from "../src/risk/pairs.ts";

const MINUTE = 60_000;
const PIP = 0.0001;

function candle(timeMs: number, open: number, high: number, low: number, close: number): BacktestCandle {
  return { timeMs, open, high, low, close };
}

function flat(timeMs: number, price: number): BacktestCandle {
  return candle(timeMs, price, price, price, price);
}

function settings(overrides: Partial<SimulationSettings> = {}): SimulationSettings {
  const pair = findPair("EURUSD");
  if (pair === undefined) {
    throw new Error("EURUSD is missing from the pair table.");
  }
  return {
    initialBalance: 10_000,
    accountCurrency: "USD",
    quoteToAccountRate: 1,
    pair,
    sizing: { mode: "fixed", lots: 1 },
    stopLossPips: 10,
    takeProfitPips: 10,
    spreadPips: 0,
    slippagePips: 0,
    commissionPerSidePerLot: 0,
    maxPositions: 1,
    minLots: 0.01,
    maxLots: 2,
    onOpposite: "reverse",
    trendEntry: "continuous",
    rewardRatio: null,
    durationMs: MINUTE,
    rsiOversold: 30,
    rsiOverbought: 70,
    ...overrides,
  };
}

function scripted(decide: (timeMs: number) => Decision): Strategy {
  return {
    id: "EMA_CROSSOVER",
    name: "script",
    rules: "Test script.",
    onBar: (bar) => decide(bar.timeMs),
  };
}

function run(
  candles: readonly BacktestCandle[],
  decide: (timeMs: number) => Decision,
  overrides: Partial<SimulationSettings> = {},
  m1: readonly BacktestCandle[] | null = null,
) {
  const configured = settings(overrides);
  const last = candles[candles.length - 1];
  return simulate({
    candles,
    m1,
    settings: configured,
    strategy: scripted(decide),
    timeframe: "M1",
    startMs: candles[0]?.timeMs ?? 0,
    endMs: (last?.timeMs ?? 0) + configured.durationMs,
  });
}

function bar(values: Partial<StrategyBar> & Pick<StrategyBar, "close">): StrategyBar {
  return {
    index: 0,
    timeMs: 0,
    open: values.close,
    high: values.close,
    low: values.close,
    ema20: null,
    ema50: null,
    ema200: null,
    previousEma20: null,
    previousEma50: null,
    previousEma200: null,
    rsi14: null,
    previousRsi14: null,
    macd: null,
    macdSignal: null,
    previousMacd: null,
    previousMacdSignal: null,
    previousClose: null,
    ...values,
  };
}

function trade(pnl: number, cost = 0, initialRisk = 100, pricePnl = pnl): ClosedTrade {
  return {
    id: 1,
    side: "buy",
    signalTimeMs: 0,
    entryTimeMs: MINUTE,
    exitTimeMs: MINUTE * 2,
    entryFill: 1.1,
    exitFill: 1.1,
    lots: 1,
    units: 100_000,
    stopLossPips: 10,
    takeProfitPips: 10,
    pnl,
    pricePnl,
    executionCost: Math.max(0, pricePnl - pnl),
    commission: 0,
    cost,
    initialRisk,
    reason: pnl >= 0 ? "target" : "stop",
  };
}

describe("position size and pip value", () => {
  it("uses the EURUSD pair table: 100,000 units and a 0.0001 pip", () => {
    const pair = findPair("EURUSD");
    expect(pair).toBeDefined();
    if (pair === undefined) {
      return;
    }
    expect(pipValuePerLot(pair, 1)).toBe(10);
    expect(pipValuePerLot(pair, 1.2)).toBeCloseTo(12);
  });

  it("sizes risk percent from balance, stop pips, and pip value", () => {
    const sized = positionLots({
      balance: 10_000,
      sizing: { mode: "risk", riskPercent: 1 },
      stopLossPips: 20,
      pipValuePerLot: 10,
      minLots: 0.01,
      maxLots: 2,
    });
    expect(sized).toEqual({ ok: true, lots: 0.5 });
  });

  it("refuses an account currency that is not the quote when no rate is supplied", () => {
    const rated = resolveQuoteRate("EURUSD", "EUR", null);
    expect(rated.ok).toBe(false);
    if (!rated.ok) {
      expect(rated.reason).toContain("No monetary result was estimated");
    }
  });
});

describe("performance metrics", () => {
  it("returns null rates for zero trades and does not produce NaN or Infinity", () => {
    const metrics = calculateMetrics([], [{ timeMs: 0, balance: 10_000, equity: 10_000 }]);
    expect(metrics.totalTrades).toBe(0);
    expect(metrics.winRate).toBeNull();
    expect(metrics.profitFactor).toBeNull();
    expect(metrics.expectancy).toBeNull();
    expect(metrics.netProfit).toBe(0);
    expect(metrics.maxDrawdown).toBe(0);
    expect(JSON.stringify(metrics)).not.toMatch(/NaN|Infinity/);
  });

  it("leaves profit factor undefined when there is no closed loss", () => {
    const metrics = calculateMetrics([trade(40, 2)], [{ timeMs: 0, balance: 10_000, equity: 10_000 }]);
    expect(metrics.profitFactor).toBeNull();
    expect(metrics.grossProfit).toBe(40);
    expect(metrics.grossLoss).toBe(0);
    expect(metrics.totalCosts).toBe(2);
  });

  it("calculates drawdown, profit factor, expectancy, and streaks from the equity curve", () => {
    const points: CurvePoint[] = [
      { timeMs: 0, balance: 100, equity: 100 },
      { timeMs: 1, balance: 150, equity: 150 },
      { timeMs: 2, balance: 90, equity: 90 },
      { timeMs: 3, balance: 120, equity: 120 },
      { timeMs: 4, balance: 80, equity: 80 },
    ];
    const metrics = calculateMetrics([
      trade(50, 1, 25),
      trade(-60, 1, 30),
      trade(30, 1, 25),
      trade(-40, 1, 20),
    ], points);
    expect(metrics.totalTrades).toBe(4);
    expect(metrics.winningTrades).toBe(2);
    expect(metrics.losingTrades).toBe(2);
    expect(metrics.winRate).toBe(0.5);
    expect(metrics.grossProfit).toBe(80);
    expect(metrics.grossLoss).toBe(100);
    expect(metrics.profitFactor).toBeCloseTo(0.8);
    expect(metrics.netProfit).toBe(-20);
    expect(metrics.averageWin).toBe(40);
    expect(metrics.averageLoss).toBe(50);
    expect(metrics.expectancy).toBe(-5);
    expect(metrics.maxConsecutiveWins).toBe(1);
    expect(metrics.maxConsecutiveLosses).toBe(1);
    expect(metrics.maxDrawdown).toBe(70);
    expect(metrics.maxDrawdownPercent).toBeCloseTo(70 / 150);
    expect(metrics.averageRiskReward).toBeCloseTo((50 / 25 + -60 / 30 + 30 / 25 + -40 / 20) / 4);
    expect(metrics.totalCosts).toBe(4);
  });
});

describe("strategy decisions use only the supplied bar", () => {
  it("implements all five strategies", () => {
    expect(listStrategies().map((strategy) => strategy.id)).toEqual([
      "EMA_CROSSOVER",
      "EMA_TREND",
      "RSI",
      "MACD",
      "EMA_RSI",
    ]);
  });

  it("buys and sells an EMA 20/50 cross and holds otherwise", () => {
    const strategy = findStrategy("EMA_CROSSOVER");
    expect(strategy.onBar(bar({
      close: 1.2,
      previousEma20: 1,
      previousEma50: 2,
      ema20: 3,
      ema50: 2,
    }), "flat")).toBe("buy");
    expect(strategy.onBar(bar({
      close: 1.2,
      previousEma20: 3,
      previousEma50: 2,
      ema20: 1,
      ema50: 2,
    }), "flat")).toBe("sell");
    expect(strategy.onBar(bar({
      close: 1.2,
      previousEma20: 3,
      previousEma50: 2,
      ema20: 4,
      ema50: 2,
    }), "buy")).toBe("hold");
  });

  it("follows a full EMA 20/50/200 stack and exits when the stack breaks", () => {
    const strategy = findStrategy("EMA_TREND");
    expect(strategy.onBar(bar({ close: 4, ema20: 3, ema50: 2, ema200: 1 }), "flat")).toBe("buy");
    expect(strategy.onBar(bar({ close: 0, ema20: 1, ema50: 2, ema200: 3 }), "flat")).toBe("sell");
    expect(strategy.onBar(bar({ close: 2.5, ema20: 3, ema50: 2, ema200: 1 }), "buy")).toBe("flat");
    const transition = findStrategy("EMA_TREND", 30, 70, "transition");
    const aligned = { close: 4, ema20: 3, ema50: 2, ema200: 1, previousClose: 4, previousEma20: 3, previousEma50: 2, previousEma200: 1 };
    expect(transition.onBar(bar(aligned), "flat")).toBe("hold");
    expect(transition.onBar(bar({ ...aligned, previousClose: 1, previousEma20: 1, previousEma50: 2, previousEma200: 3 }), "flat")).toBe("buy");
    expect(strategy.onBar(bar({ close: 4, ema20: 3, ema50: 2, ema200: null }), "flat")).toBe("hold");
  });

  it("uses configurable RSI crosses", () => {
    const strategy = findStrategy("RSI", 30, 70);
    expect(strategy.onBar(bar({ close: 1, previousRsi14: 29, rsi14: 31 }), "flat")).toBe("buy");
    expect(strategy.onBar(bar({ close: 1, previousRsi14: 71, rsi14: 69 }), "flat")).toBe("sell");
    expect(strategy.onBar(bar({ close: 1, previousRsi14: 40, rsi14: 50 }), "flat")).toBe("hold");
    const tight = findStrategy("RSI", 40, 60);
    expect(tight.onBar(bar({ close: 1, previousRsi14: 39, rsi14: 41 }), "flat")).toBe("buy");
  });

  it("buys and sells a MACD cross", () => {
    const strategy = findStrategy("MACD");
    expect(strategy.onBar(bar({
      close: 1,
      previousMacd: 0,
      previousMacdSignal: 1,
      macd: 2,
      macdSignal: 1,
    }), "flat")).toBe("buy");
    expect(strategy.onBar(bar({
      close: 1,
      previousMacd: 2,
      previousMacdSignal: 1,
      macd: 0,
      macdSignal: 1,
    }), "flat")).toBe("sell");
  });

  it("requires EMA alignment before an EMA plus RSI entry and exits when alignment breaks", () => {
    const strategy = findStrategy("EMA_RSI");
    expect(strategy.onBar(bar({
      close: 1,
      ema20: 2,
      ema50: 1,
      previousRsi14: 20,
      rsi14: 35,
    }), "flat")).toBe("buy");
    expect(strategy.onBar(bar({
      close: 1,
      ema20: 1,
      ema50: 2,
      previousRsi14: 20,
      rsi14: 35,
    }), "flat")).toBe("hold");
    expect(strategy.onBar(bar({
      close: 1,
      ema20: 1,
      ema50: 2,
      previousRsi14: 40,
      rsi14: 45,
    }), "buy")).toBe("flat");
  });
});

describe("execution simulation", () => {
  it("fills a buy at the next open and pays the configured spread", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE, 1.101, 1.105, 1.101, 1.105),
    ], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "fixed", lots: 0.1 },
      stopLossPips: 20,
      takeProfitPips: 40,
      spreadPips: 1,
      slippagePips: 0,
    });
    expect(result.trades).toHaveLength(1);
    const filled = result.trades[0];
    expect(filled?.signalTimeMs).toBe(0);
    expect(filled?.entryTimeMs).toBe(MINUTE);
    expect(filled?.entryFill).toBeCloseTo(1.1011, 6);
    expect(filled?.exitFill).toBeCloseTo(1.105, 6);
    expect(filled?.reason).toBe("target");
    expect(filled?.pnl).toBeCloseTo(39);
    expect(filled?.cost).toBeCloseTo(1);
    expect(result.finalBalance).toBeCloseTo(10_039);
    expect(result.metrics.netProfit).toBeCloseTo(39);
  });

  it("fills a sell at the bid and reaches an ask target without charging spread twice", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1, 1.0989, 1.099),
    ], (time) => time === 0 ? "sell" : "hold", {
      spreadPips: 1,
      slippagePips: 0,
      takeProfitPips: 10,
      stopLossPips: 10,
    });
    const filled = result.trades[0];
    expect(filled?.side).toBe("sell");
    expect(filled?.entryFill).toBeCloseTo(1.1, 6);
    expect(filled?.exitFill).toBeCloseTo(1.099, 6);
    expect(filled?.reason).toBe("target");
    expect(filled?.pnl).toBeCloseTo(100);
    expect(filled?.pricePnl - filled!.executionCost - filled!.commission).toBeCloseTo(filled!.pnl);
    expect(filled?.cost).toBeCloseTo(10);
  });

  it("stops a buy and a sell at the configured bid", () => {
    const buy = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1, 1.099, 1.099),
    ], (time) => time === 0 ? "buy" : "hold");
    expect(buy.trades[0]?.reason).toBe("stop");
    expect(buy.trades[0]?.pnl).toBeCloseTo(-100);

    const sell = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.101, 1.1, 1.101),
    ], (time) => time === 0 ? "sell" : "hold");
    expect(sell.trades[0]?.reason).toBe("stop");
    expect(sell.trades[0]?.pnl).toBeCloseTo(-100);
  });

  it("fills the stop when one bar touches both stop and target", () => {
    const result = run([
      flat(0, 1.101),
      candle(MINUTE, 1.101, 1.105, 1.098, 1.102),
    ], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "fixed", lots: 0.1 },
      stopLossPips: 20,
      takeProfitPips: 40,
      spreadPips: 1,
    });
    expect(result.trades[0]?.reason).toBe("stop");
    expect(result.trades[0]?.exitFill).toBeCloseTo(1.099, 6);
    expect(result.trades[0]?.pnl).toBeCloseTo(-21);
  });

  it("uses the opened bid when price gaps through the stop", () => {
    const result = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      candle(MINUTE * 2, 1.098, 1.0985, 1.098, 1.0982),
    ], (time) => time === 0 ? "buy" : "hold", { stopLossPips: 10, takeProfitPips: 50 });
    expect(result.trades[0]?.reason).toBe("stop");
    expect(result.trades[0]?.exitFill).toBeCloseTo(1.098, 6);
    expect(result.trades[0]?.pnl).toBeCloseTo(-200);
  });

  it("applies slippage on the entry and the exit", () => {
    const result = run([
      flat(0, 1.101),
      candle(MINUTE, 1.101, 1.105, 1.101, 1.105),
    ], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "fixed", lots: 0.1 },
      stopLossPips: 20,
      takeProfitPips: 40,
      spreadPips: 1,
      slippagePips: 0.2,
    });
    expect(result.trades[0]?.entryFill).toBeCloseTo(1.10112, 6);
    expect(result.trades[0]?.exitFill).toBeCloseTo(1.10498, 6);
    expect(result.trades[0]?.pnl).toBeCloseTo(38.6);
    expect(result.trades[0]?.cost).toBeCloseTo(1.4);
  });

  it("scans M1 in time order and does not use a later stop when the target came first", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE * 2, 1.1, 1.102, 1.098, 1.1),
      flat(MINUTE * 4, 1.1),
    ], (time) => time === 0 ? "buy" : "hold", {
      durationMs: MINUTE * 2,
      stopLossPips: 10,
      takeProfitPips: 10,
    }, [
      candle(MINUTE * 2, 1.1, 1.101, 1.1, 1.1005),
      candle(MINUTE * 3, 1.1, 1.1, 1.098, 1.0985),
    ]);
    expect(result.trades[0]?.reason).toBe("target");
    expect(result.trades[0]?.exitTimeMs).toBe(MINUTE * 2);
    expect(result.trades[0]?.pnl).toBeCloseTo(100);
    expect(result.warnings.some((warning) => warning.includes("complete M1"))).toBe(false);
  });

  it("stops on the first M1 bar even when a later M1 bar reaches the target", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE * 2, 1.1, 1.102, 1.098, 1.1),
      flat(MINUTE * 4, 1.1),
    ], (time) => time === 0 ? "buy" : "hold", { durationMs: MINUTE * 2 }, [
      candle(MINUTE * 2, 1.1, 1.1, 1.099, 1.0995),
      candle(MINUTE * 3, 1.099, 1.101, 1.099, 1.101),
    ]);
    expect(result.trades[0]?.reason).toBe("stop");
    expect(result.trades[0]?.exitTimeMs).toBe(MINUTE * 2);
  });

  it("does not open a trade from a signal on the last candle", () => {
    const seen: number[] = [];
    const result = simulate({
      candles: [flat(0, 1), flat(MINUTE, 1), flat(MINUTE * 2, 2)],
      m1: null,
      settings: settings(),
      strategy: {
        id: "RSI",
        name: "lookahead",
        rules: "Buy only after the close is above 1.5.",
        onBar: (bar) => {
          seen.push(bar.close);
          return bar.close > 1.5 ? "buy" : "hold";
        },
      },
      timeframe: "M1",
      startMs: 0,
      endMs: MINUTE * 3,
    });
    expect(seen).toEqual([1, 1, 2]);
    expect(result.trades).toHaveLength(0);
    expect(result.metrics.winRate).toBeNull();
    expect(result.metrics.profitFactor).toBeNull();
    expect(result.finalBalance).toBe(10_000);
  });

  it("rejects candles that are not in increasing time order", () => {
    expect(() => run([flat(MINUTE, 1.1), flat(0, 1.1)], () => "hold")).toThrow(/increasing time order/);
  });

  it("cancels a pending signal across an unclassified hole and does not invent a candle", () => {
    const result = run([
      flat(0, 1.1),
      flat(MINUTE * 3, 1.2),
    ], (time) => time === 0 ? "buy" : "hold");
    expect(result.trades).toHaveLength(0);
    expect(result.openTrades).toHaveLength(0);
    expect(result.warnings.some((warning) => warning.includes("No candles were invented"))).toBe(true);
  });

  it("closes an open position at the next real open after a hole", () => {
    const result = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      flat(MINUTE * 3, 1.102),
    ], (time) => time === 0 ? "buy" : "hold", { stopLossPips: 50, takeProfitPips: 50 });
    expect(result.trades).toHaveLength(1);
    expect(result.trades[0]?.reason).toBe("gap");
    expect(result.trades[0]?.exitFill).toBeCloseTo(1.102);
    expect(result.trades[0]?.pnl).toBeCloseTo(200);
  });

  it("keeps an expected closure in one series when the caller classifies it", () => {
    const result = simulate({
      candles: [flat(0, 1.1), flat(MINUTE * 3, 1.1)],
      m1: null,
      settings: settings({ stopLossPips: 50, takeProfitPips: 50 }),
      strategy: scripted((time) => time === 0 ? "buy" : "hold"),
      timeframe: "M1",
      startMs: 0,
      endMs: MINUTE * 4,
      gapClassifier: () => "EXPECTED_MARKET_CLOSURE",
    });
    expect(result.warnings.some((warning) => warning.includes("gap"))).toBe(false);
    expect(result.openTrades[0]?.entryTimeMs).toBe(MINUTE * 3);
    expect(result.metrics.netProfit).toBe(0);
  });

  it("opens a second position only when the maximum allows it", () => {
    const result = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      flat(MINUTE * 2, 1.1),
      flat(MINUTE * 3, 1.1),
    ], () => "buy", { stopLossPips: 50, takeProfitPips: 50, maxPositions: 2 });
    expect(result.openTrades).toHaveLength(2);
    expect(result.metrics.netProfit).toBe(0);
  });

  it("does not open a second position in the same direction", () => {
    const result = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      flat(MINUTE * 2, 1.1),
      flat(MINUTE * 3, 1.1),
    ], () => "buy", { stopLossPips: 50, takeProfitPips: 50 });
    expect(result.trades).toHaveLength(0);
    expect(result.openTrades).toHaveLength(1);
    expect(result.metrics.netProfit).toBe(0);
    expect(result.finalBalance).toBe(10_000);
  });

  it("reverses on an opposite signal and can instead only close", () => {
    const candles = [flat(0, 1.1), flat(MINUTE, 1.1), candle(MINUTE * 2, 1.101, 1.101, 1.101, 1.101)];
    const decide = (time: number): Decision => {
      if (time === 0) {
        return "buy";
      }
      if (time === MINUTE) {
        return "sell";
      }
      return "hold";
    };
    const reversed = run(candles, decide, { stopLossPips: 50, takeProfitPips: 50 });
    expect(reversed.trades[0]?.reason).toBe("signal");
    expect(reversed.trades[0]?.pnl).toBeCloseTo(100);
    expect(reversed.openTrades[0]?.side).toBe("sell");

    const closed = run(candles, decide, { stopLossPips: 50, takeProfitPips: 50, onOpposite: "close" });
    expect(closed.trades).toHaveLength(1);
    expect(closed.openTrades).toHaveLength(0);
    expect(closed.finalBalance).toBeCloseTo(10_100);
  });

  it("ignores an opposite signal when that policy is selected", () => {
    const result = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      flat(MINUTE * 2, 1.1),
    ], (time) => time === 0 ? "buy" : "sell", { stopLossPips: 50, takeProfitPips: 50, onOpposite: "ignore" });
    expect(result.trades).toHaveLength(0);
    expect(result.openTrades[0]?.side).toBe("buy");
  });

  it("loses exactly the risk amount on a stop when costs are zero", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1, 1.098, 1.098),
    ], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "risk", riskPercent: 1 },
      stopLossPips: 20,
      takeProfitPips: 40,
    });
    expect(result.trades[0]?.lots).toBeCloseTo(0.5);
    expect(result.trades[0]?.pnl).toBeCloseTo(-100);
    expect(result.finalBalance).toBeCloseTo(9_900);
  });

  it("skips an entry below the minimum or above the maximum lot", () => {
    const small = run([flat(0, 1.1), flat(MINUTE, 1.1)], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "risk", riskPercent: 0.01 },
      stopLossPips: 50,
    });
    expect(small.skippedEntries).toBe(1);
    expect(small.trades).toHaveLength(0);

    const large = run([flat(0, 1.1), flat(MINUTE, 1.1)], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "fixed", lots: 3 },
    });
    expect(large.skippedEntries).toBe(1);
    expect(large.finalBalance).toBe(10_000);
  });

  it("deducts commission once per side and keeps closed profit equal to the balance change", () => {
    const result = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      flat(MINUTE * 2, 1.1),
    ], (time) => time === 0 ? "buy" : time === MINUTE ? "sell" : "hold", {
      commissionPerSidePerLot: 7,
      stopLossPips: 50,
      takeProfitPips: 50,
      onOpposite: "close",
    });
    expect(result.trades[0]?.pnl).toBeCloseTo(-14);
    expect(result.trades[0]?.cost).toBeCloseTo(14);
    expect(result.metrics.totalCosts).toBeCloseTo(14);
    expect(result.finalBalance).toBeCloseTo(9_986);
    expect(result.openTrades).toHaveLength(0);
  });

  it("tracks drawdown from a win followed by a loss and leaves an open trade out of net profit", () => {
    const closed = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.101, 1.1, 1.101),
      flat(MINUTE * 2, 1.1),
      candle(MINUTE * 3, 1.1, 1.1, 1.099, 1.099),
    ], (time) => time === 0 || time === MINUTE * 2 ? "buy" : "hold");
    expect(closed.metrics.profitFactor).toBeCloseTo(1);
    expect(closed.metrics.maxDrawdown).toBeCloseTo(100);
    expect(closed.metrics.maxDrawdownPercent).toBeCloseTo(100 / 10_100);
    expect(closed.finalBalance).toBeCloseTo(10_000);

    const open = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.101, 1.1, 1.101),
    ], (time) => time === 0 ? "buy" : "hold", { stopLossPips: 50, takeProfitPips: 50 });
    expect(open.metrics.netProfit).toBe(0);
    expect(open.metrics.totalTrades).toBe(0);
    expect(open.finalBalance).toBe(10_000);
    expect(open.finalEquity).toBeCloseTo(10_100);
    expect(open.warnings.some((warning) => warning.includes("not counted as closed profit"))).toBe(true);
  });

  it("falls back to the parent bar when the M1 path is incomplete", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE * 2, 1.1, 1.102, 1.098, 1.1),
      flat(MINUTE * 4, 1.1),
    ], (time) => time === 0 ? "buy" : "hold", { durationMs: MINUTE * 2 }, [
      candle(MINUTE * 2, 1.1, 1.101, 1.1, 1.1005),
    ]);
    expect(result.trades[0]?.reason).toBe("stop");
    expect(result.warnings.some((warning) => warning.includes("complete M1"))).toBe(true);
  });

  it("separates in-sample and out-of-sample walk-forward windows", () => {
    const candles = Array.from({ length: 12 }, (_, index) => flat(index * MINUTE, 1.1));
    const input = {
      candles,
      m1: null,
      settings: settings({ stopLossPips: 50, takeProfitPips: 50 }),
      strategy: scripted((time) => (time / MINUTE) % 2 === 0 ? "buy" : "sell"),
      timeframe: "M1" as const,
      startMs: 0,
      endMs: 12 * MINUTE,
    };
    const folds = walkForward(input, 2, 0.5);
    expect(folds).toHaveLength(2);
    expect(folds[0]?.inSample.label).toBe("in_sample");
    expect(folds[0]?.outOfSample.label).toBe("out_of_sample");
    for (const fold of folds) {
      for (const trade of fold.outOfSample.trades) {
        expect(trade.signalTimeMs).toBeGreaterThanOrEqual(fold.outOfSample.startMs);
      }
      for (const trade of fold.inSample.trades) {
        expect(trade.signalTimeMs).toBeLessThan(fold.outOfSample.startMs);
      }
    }
  });
});

describe("short ask triggers and corrected accounting", () => {
  const shortCosts = {
    sizing: { mode: "fixed" as const, lots: 0.1 },
    stopLossPips: 30,
    takeProfitPips: 60,
    spreadPips: 1,
    slippagePips: 0.2,
  };

  it("triggers a short stop when the ask, not merely the bid, reaches 1.10300", () => {
    const triggered = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1029, 1.1, 1.1029),
    ], (time) => time === 0 ? "sell" : "hold", shortCosts);
    expect(triggered.trades[0]?.reason).toBe("stop");
    expect(triggered.trades[0]?.entryFill).toBeCloseTo(1.09998, 6);
    expect(triggered.trades[0]?.exitFill).toBeCloseTo(1.10302, 6);
    expect(triggered.trades[0]?.pnl).toBeCloseTo(-30.4, 6);

    const through = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.10295, 1.1, 1.10295),
    ], (time) => time === 0 ? "sell" : "hold", shortCosts);
    expect(through.trades[0]?.reason).toBe("stop");
    expect(through.trades[0]?.exitFill).toBeCloseTo(1.10302, 6);

    const missed = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.10289, 1.1, 1.10289),
    ], (time) => time === 0 ? "sell" : "hold", shortCosts);
    expect(missed.trades).toHaveLength(0);
    expect(missed.openTrades).toHaveLength(1);
  });

  it("triggers a short target only when the ask reaches 1.09400", () => {
    const missed = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1, 1.094, 1.094),
    ], (time) => time === 0 ? "sell" : "hold", shortCosts);
    expect(missed.trades).toHaveLength(0);

    const hit = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1, 1.0939, 1.094),
    ], (time) => time === 0 ? "sell" : "hold", shortCosts);
    expect(hit.trades[0]?.reason).toBe("target");
    expect(hit.trades[0]?.exitFill).toBeCloseTo(1.09402, 6);
    expect(hit.trades[0]?.pnl).toBeCloseTo(59.6, 6);
    expect(hit.trades[0]!.pricePnl - hit.trades[0]!.executionCost - hit.trades[0]!.commission).toBeCloseTo(hit.trades[0]!.pnl);
  });

  it("fills a short gap at the opened ask plus exit slippage and keeps stop-first", () => {
    const gap = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      candle(MINUTE * 2, 1.104, 1.1042, 1.104, 1.1041),
    ], (time) => time === 0 ? "sell" : "hold", shortCosts);
    expect(gap.trades[0]?.reason).toBe("stop");
    expect(gap.trades[0]?.exitFill).toBeCloseTo(1.10412, 6);
    expect(gap.trades[0]!.pnl).toBeLessThan(-30.4);

    const both = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.10295, 1.0939, 1.1),
    ], (time) => time === 0 ? "sell" : "hold", shortCosts);
    expect(both.trades[0]?.reason).toBe("stop");
    expect(both.trades[0]?.exitFill).toBeCloseTo(1.10302, 6);
  });

  it("uses the same ask rule when the parent bar is the fallback", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE * 2, 1.1, 1.10295, 1.1, 1.102),
      flat(MINUTE * 4, 1.1),
    ], (time) => time === 0 ? "sell" : "hold", { ...shortCosts, durationMs: MINUTE * 2 }, [
      candle(MINUTE * 2, 1.1, 1.1001, 1.1, 1.1001),
    ]);
    expect(result.trades[0]?.reason).toBe("stop");
    expect(result.warnings.some((warning) => warning.includes("complete M1"))).toBe(true);
  });

  it("keeps a long stop on the bid and a long gap at the opened bid", () => {
    const stop = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1, 1.097, 1.097),
    ], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "fixed", lots: 0.1 },
      stopLossPips: 30,
      takeProfitPips: 60,
      spreadPips: 1,
      slippagePips: 0.2,
    });
    expect(stop.trades[0]?.entryFill).toBeCloseTo(1.10012, 6);
    expect(stop.trades[0]?.exitFill).toBeCloseTo(1.09698, 6);
    expect(stop.trades[0]?.pnl).toBeCloseTo(-31.4, 6);

    const gap = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      candle(MINUTE * 2, 1.096, 1.0962, 1.096, 1.0961),
    ], (time) => time === 0 ? "buy" : "hold", {
      stopLossPips: 30,
      takeProfitPips: 60,
      spreadPips: 0,
      slippagePips: 0,
    });
    expect(gap.trades[0]?.reason).toBe("stop");
    expect(gap.trades[0]?.exitFill).toBeCloseTo(1.096, 6);
  });

  it("reserves the clean-stop loss for risk percent and lets a gap exceed it", () => {
    const sized = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1, 1.097, 1.097),
    ], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "risk", riskPercent: 1 },
      stopLossPips: 30,
      takeProfitPips: 60,
      spreadPips: 1,
      slippagePips: 0.2,
    });
    expect(sized.trades[0]?.pnl).toBeCloseTo(-100, 6);
    expect(sized.finalBalance).toBeCloseTo(9_900, 6);

    const gapped = run([
      flat(0, 1.1),
      flat(MINUTE, 1.1),
      candle(MINUTE * 2, 1.096, 1.096, 1.096, 1.096),
    ], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "risk", riskPercent: 1 },
      stopLossPips: 30,
      takeProfitPips: 60,
      spreadPips: 1,
      slippagePips: 0.2,
    });
    expect(gapped.trades[0]?.pnl).toBeLessThan(-100);
  });

  it("keeps fixed lots unchanged when costs are configured", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.1, 1.097, 1.097),
    ], (time) => time === 0 ? "buy" : "hold", {
      sizing: { mode: "fixed", lots: 0.2 },
      stopLossPips: 30,
      spreadPips: 1,
      slippagePips: 0.2,
    });
    expect(result.trades[0]?.lots).toBeCloseTo(0.2, 8);
  });

  it("rejects an M1 path that skips a minute instead of treating the row count as coverage", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE * 2, 1.1, 1.102, 1.098, 1.1),
      flat(MINUTE * 4, 1.1),
    ], (time) => time === 0 ? "buy" : "hold", { durationMs: MINUTE * 2 }, [
      candle(MINUTE * 2, 1.1, 1.1002, 1.1, 1.1001),
      candle(MINUTE * 2 + 30_000, 1.1, 1.1002, 1.1, 1.1001),
    ]);
    expect(result.trades[0]?.reason).toBe("stop");
    expect(result.warnings.some((warning) => warning.includes("complete M1"))).toBe(true);
  });

  it("marks a short against the ask high inside the bar", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.101, 1.1, 1.1),
    ], (time) => time === 0 ? "sell" : "hold", {
      stopLossPips: 50,
      takeProfitPips: 50,
      spreadPips: 1,
      slippagePips: 0,
    });
    expect(result.trades).toHaveLength(0);
    expect(result.metrics.maxDrawdown).toBeCloseTo(110, 4);
  });

  it("marks intrabar adverse equity and keeps the balance curve separate", () => {
    const result = run([
      flat(0, 1.1),
      candle(MINUTE, 1.1, 1.101, 1.099, 1.1),
    ], (time) => time === 0 ? "buy" : "hold", {
      stopLossPips: 50,
      takeProfitPips: 50,
      spreadPips: 0,
      slippagePips: 0,
    });
    expect(result.trades).toHaveLength(0);
    expect(result.finalEquity).toBeCloseTo(10_000, 6);
    expect(result.metrics.maxDrawdown).toBeCloseTo(100, 6);
    expect(result.equityCurve).not.toBe(result.balanceCurve);
    expect(result.equityCurve[result.equityCurve.length - 1]?.equity).toBeCloseTo(10_000, 6);
    expect(result.balanceCurve[result.balanceCurve.length - 1]?.equity).toBeCloseTo(10_000, 6);
    expect(result.balanceCurve[result.balanceCurve.length - 1]?.balance).toBe(10_000);
  });

  it("rejects impossible UTC dates, reversed ranges, and conflicting settings", async () => {
    expect(parseUtcDate("2024-02-29")).toBe(Date.UTC(2024, 1, 29));
    expect(parseUtcDate("2024-02-31")).toBeNull();
    expect(parseUtcDate("2024-13-01")).toBeNull();
    expect(parseUtcDate("2024-00-10")).toBeNull();
    expect(parseUtcDate("2024-04-31")).toBeNull();
    expect(parseUtcDate("2024-2-29")).toBeNull();
    for (const argument of [
      "EURUSD 2024-02-31 2024-03-01",
      "EURUSD 2024-13-01 2024-03-01",
      "EURUSD 2024-00-10 2024-03-01",
      "EURUSD 2024-04-31 2024-05-01",
      "EURUSD 2024-05-02 2024-05-01",
      "EURUSD tp=40 rr=2",
      "EURUSD risk=1 lots=0.1",
      "EURUSD risk=1 risk=2",
    ]) {
      const replies = await buildBacktestReplies(argument);
      expect(replies.join("\n")).not.toContain("Final balance");
    }
  });

  it("reports the settings used for the run", () => {
    const result = run([flat(0, 1.1)], () => "hold", {
      spreadPips: 1,
      slippagePips: 0.2,
      sizing: { mode: "fixed", lots: 0.1 },
    });
    const text = formatBacktestReport(result);
    expect(text).toContain("Spread: 1 pips");
    expect(text).toContain("Slippage: 0.2 pips");
    expect(text).toContain("fixed 0.1 lots");
    expect(text).toContain("Stop loss: 10 pips");
    expect(text).toContain("Ask = bid + this spread");
    expect(text).toContain("Leverage, margin, swaps");
    expect(text).toContain("Open profit is not included");
  });
});

describe("historical loader and Telegram command", () => {
  it("keeps the native timeframe and drops a candle that has not closed", () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-backtest-"));
    const day = join(root, "H1");
    mkdirSync(day);
    writeFileSync(join(day, "2024-06-03.csv"), [
      "time_utc,open,high,low,close,volume",
      "2024-06-03 00:00:00,1.1000,1.1010,1.0990,1.1005,10",
      "2024-06-03 01:00:00,1.1005,1.1020,1.1000,1.1015,11",
      "",
    ].join("\n"));
    const loaded = loadClosedCandles({
      timeframe: "H1",
      startMs: Date.UTC(2024, 5, 3),
      endMs: Date.UTC(2024, 5, 3, 1, 0, 0),
      root,
      m1Root: join(root, "missing-m1"),
    });
    expect(loaded.ok).toBe(true);
    if (!loaded.ok) {
      return;
    }
    expect(loaded.candles).toEqual([
      candle(Date.UTC(2024, 5, 3), 1.1, 1.101, 1.099, 1.1005),
    ]);
    expect(loaded.m1).toEqual([]);
    expect(loaded.warnings[0]).toContain("M1 execution candles were not loaded");
  });

  it("rejects a malformed historical file instead of repairing it", () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-backtest-"));
    writeFileSync(join(root, "2024-06-03.csv"), "not,a,candle,file\n");
    const loaded = loadClosedCandles({
      timeframe: "M1",
      startMs: Date.UTC(2024, 5, 3),
      endMs: Date.UTC(2024, 5, 4),
      m1Root: root,
    });
    expect(loaded.ok).toBe(false);
  });

  it("explains the command and rejects an unsupported instrument", async () => {
    const blank = await buildBacktestReplies("");
    expect(blank[0]).toContain("Usage: /backtest");
    const unknown = await buildBacktestReplies("BTCUSD");
    expect(unknown[0]).toContain("not a supported instrument");
    const missing = await buildBacktestReplies("GBPUSD H1");
    expect(missing.join("\n")).toContain("no native historical candles");
    expect(missing.join("\n")).not.toContain("Final balance");
  });

  it("runs a fixture backtest through the command and splits a long reply", async () => {
    const root = mkdtempSync(join(tmpdir(), "tradexa-backtest-"));
    const h1 = join(root, "H1");
    mkdirSync(h1);
    writeFileSync(join(h1, "2024-06-03.csv"), [
      "time_utc,open,high,low,close,volume",
      "2024-06-03 00:00:00,1.1000,1.1000,1.1000,1.1000,1",
      "2024-06-03 01:00:00,1.1000,1.1010,1.0990,1.1000,1",
      "2024-06-03 02:00:00,1.1000,1.1000,1.1000,1.1000,1",
      "",
    ].join("\n"));
    const replies = await buildBacktestReplies(
      "EURUSD EMA_CROSSOVER H1 2024-06-03 2024-06-03 lots=0.1 sl=10 tp=10 spread=1",
      { root, m1Root: join(root, "no-m1"), splitLimit: 180 },
    );
    const text = replies.join("\n");
    expect(replies.length).toBeGreaterThan(1);
    expect(text).toContain("EURUSD");
    expect(text).toContain("EMA 20/50 crossover");
    expect(text).toContain("H1");
    expect(text).toContain("historical simulation");
    expect(text).toContain("not future performance");
    expect(text).toContain("Commission is 0");
    const refused = await buildBacktestReplies("EURUSD H1 currency=EUR", { root });
    expect(refused.join("\n")).toContain("No monetary result was estimated");
    const bad = await buildBacktestReplies("EURUSD nope=1");
    expect(bad[0]).toContain("not a backtest setting");
  });

  it("mentions /backtest in help and start", () => {
    expect(renderHelpReply()).toContain("/backtest");
    expect(renderStartReply()).toContain("/backtest");
  });

  it("formats a zero-trade report without claiming a profit", () => {
    const result = run([flat(0, 1.1)], () => "hold");
    const text = formatBacktestReport(result);
    expect(text).toContain("Total closed trades: 0");
    expect(text).toContain("n/a");
    expect(text).not.toMatch(/profitable|guaranteed/i);
  });
});
