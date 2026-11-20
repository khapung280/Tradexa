import { crossoverFromDiffs } from "../analysis/indicators.js";
import type { Decision, Strategy, StrategyBar, StrategyId, TrendEntry } from "./types.js";

export function listStrategies(
  rsiOversold = 30,
  rsiOverbought = 70,
  trendEntry: TrendEntry = "continuous",
): readonly Strategy[] {
  return [
    emaCrossoverStrategy(),
    emaTrendStrategy(trendEntry),
    rsiStrategy(rsiOversold, rsiOverbought),
    macdStrategy(),
    emaRsiStrategy(rsiOversold, rsiOverbought),
  ];
}

export function findStrategy(
  id: StrategyId,
  rsiOversold = 30,
  rsiOverbought = 70,
  trendEntry: TrendEntry = "continuous",
): Strategy {
  const found = listStrategies(rsiOversold, rsiOverbought, trendEntry).find((strategy) => strategy.id === id);
  if (found === undefined) {
    throw new Error(`Unknown strategy ${id}.`);
  }
  return found;
}

export function parseStrategyId(raw: string): StrategyId | null {
  const token = raw.trim().toUpperCase().replace(/[\s-]+/g, "_");
  if (token === "EMA" || token === "EMA_CROSS" || token === "EMA_CROSSOVER") {
    return "EMA_CROSSOVER";
  }
  if (token === "EMA_TREND" || token === "TREND") {
    return "EMA_TREND";
  }
  if (token === "RSI") {
    return "RSI";
  }
  if (token === "MACD") {
    return "MACD";
  }
  if (token === "EMA_RSI" || token === "EMARSI") {
    return "EMA_RSI";
  }
  return null;
}

function emaCrossoverStrategy(): Strategy {
  return {
    id: "EMA_CROSSOVER",
    name: "EMA 20/50 crossover",
    rules: "At the close, buy when EMA 20 crosses above EMA 50. Sell when EMA 20 crosses below EMA 50. Hold between crosses. The fill is the next candle open. Stop and target come from the configured pip distances.",
    onBar: (bar) => {
      if (
        bar.previousEma20 === null ||
        bar.previousEma50 === null ||
        bar.ema20 === null ||
        bar.ema50 === null
      ) {
        return "hold";
      }
      const cross = crossoverFromDiffs(
        bar.previousEma20 - bar.previousEma50,
        bar.ema20 - bar.ema50,
      );
      if (cross === "bullish") {
        return "buy";
      }
      if (cross === "bearish") {
        return "sell";
      }
      return "hold";
    },
  };
}

function emaTrendStrategy(entry: TrendEntry): Strategy {
  const continuous = entry === "continuous";
  return {
    id: "EMA_TREND",
    name: "EMA 20/50/200 trend",
    rules: continuous
      ? "Buy while EMA 20 is above EMA 50, EMA 50 is above EMA 200, and the close is above EMA 20. Sell while the stack is reversed and the close is below EMA 20. This continuous rule can enter again after a stop while the stack remains valid. Exit to flat when the stack breaks. The fill is the next candle open."
      : "Buy only on the bar where the bullish EMA 20/50/200 stack becomes true, and sell only on the bar where the bearish stack becomes true. A later stop does not re-enter until the stack breaks and forms again. Exit to flat when the stack breaks. The fill is the next candle open.",
    onBar: (bar) => {
      if (bar.ema20 === null || bar.ema50 === null || bar.ema200 === null) {
        return "hold";
      }
      const bullish = bar.ema20 > bar.ema50 && bar.ema50 > bar.ema200 && bar.close > bar.ema20;
      const bearish = bar.ema20 < bar.ema50 && bar.ema50 < bar.ema200 && bar.close < bar.ema20;
      if (!continuous) {
        const previousBullish = bar.previousEma20 !== null
          && bar.previousEma50 !== null
          && bar.previousEma200 !== null
          && bar.previousClose !== null
          && bar.previousEma20 > bar.previousEma50
          && bar.previousEma50 > bar.previousEma200
          && bar.previousClose > bar.previousEma20;
        const previousBearish = bar.previousEma20 !== null
          && bar.previousEma50 !== null
          && bar.previousEma200 !== null
          && bar.previousClose !== null
          && bar.previousEma20 < bar.previousEma50
          && bar.previousEma50 < bar.previousEma200
          && bar.previousClose < bar.previousEma20;
        if (bullish && !previousBullish) {
          return "buy";
        }
        if (bearish && !previousBearish) {
          return "sell";
        }
        if (!bullish && !bearish) {
          return "flat";
        }
        return "hold";
      }
      if (bullish) {
        return "buy";
      }
      if (bearish) {
        return "sell";
      }
      return "flat";
    },
  };
}

function rsiStrategy(oversold: number, overbought: number): Strategy {
  return {
    id: "RSI",
    name: "RSI 14 reversal",
    rules: `Buy when RSI 14 crosses up through ${oversold}. Sell when RSI 14 crosses down through ${overbought}. Hold until the opposite cross. The fill is the next candle open. Thresholds are settings, not predictions.`,
    onBar: (bar) => crossRsi(bar, oversold, overbought),
  };
}

function macdStrategy(): Strategy {
  return {
    id: "MACD",
    name: "MACD 12/26/9 crossover",
    rules: "Buy when the MACD line crosses above its signal line. Sell when it crosses below. The cross uses the Phase 7 rule: the previous difference is at or on one side of zero and the current difference is strictly on the other side. Hold between crosses. The fill is the next candle open.",
    onBar: (bar) => {
      if (
        bar.previousMacd === null ||
        bar.previousMacdSignal === null ||
        bar.macd === null ||
        bar.macdSignal === null
      ) {
        return "hold";
      }
      const cross = crossoverFromDiffs(
        bar.previousMacd - bar.previousMacdSignal,
        bar.macd - bar.macdSignal,
      );
      if (cross === "bullish") {
        return "buy";
      }
      if (cross === "bearish") {
        return "sell";
      }
      return "hold";
    },
  };
}

function emaRsiStrategy(oversold: number, overbought: number): Strategy {
  return {
    id: "EMA_RSI",
    name: "EMA 20/50 plus RSI 14",
    rules: `Buy only when EMA 20 is above EMA 50 and RSI 14 crosses up through ${oversold}. Sell only when EMA 20 is below EMA 50 and RSI 14 crosses down through ${overbought}. Exit when the open position's EMA alignment is no longer true. The fill is the next candle open.`,
    onBar: (bar, position) => {
      if (bar.ema20 === null || bar.ema50 === null) {
        return "hold";
      }
      const longAligned = bar.ema20 > bar.ema50;
      const shortAligned = bar.ema20 < bar.ema50;
      if (position === "buy" && !longAligned) {
        return "flat";
      }
      if (position === "sell" && !shortAligned) {
        return "flat";
      }
      const cross = crossRsi(bar, oversold, overbought);
      if (cross === "buy" && longAligned) {
        return "buy";
      }
      if (cross === "sell" && shortAligned) {
        return "sell";
      }
      return "hold";
    },
  };
}

function crossRsi(bar: StrategyBar, oversold: number, overbought: number): Decision {
  if (bar.previousRsi14 === null || bar.rsi14 === null) {
    return "hold";
  }
  if (bar.previousRsi14 <= oversold && bar.rsi14 > oversold) {
    return "buy";
  }
  if (bar.previousRsi14 >= overbought && bar.rsi14 < overbought) {
    return "sell";
  }
  return "hold";
}
