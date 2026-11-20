import type { ForexPair } from "../risk/pairs.js";
import type { TimeframeId } from "../market/timeframes.js";

export const STRATEGY_IDS = [
  "EMA_CROSSOVER",
  "EMA_TREND",
  "RSI",
  "MACD",
  "EMA_RSI",
] as const;
export type StrategyId = (typeof STRATEGY_IDS)[number];

export const DECISIONS = ["buy", "sell", "flat", "hold"] as const;
export type Decision = (typeof DECISIONS)[number];

export type PositionSide = "buy" | "sell";

export type BacktestCandle = {
  timeMs: number;
  open: number;
  high: number;
  low: number;
  close: number;
};

/** Values the strategy may see. There is no later candle in this object. */
export type StrategyBar = {
  index: number;
  timeMs: number;
  open: number;
  high: number;
  low: number;
  close: number;
  ema20: number | null;
  ema50: number | null;
  ema200: number | null;
  previousEma20: number | null;
  previousEma50: number | null;
  previousEma200: number | null;
  rsi14: number | null;
  previousRsi14: number | null;
  macd: number | null;
  macdSignal: number | null;
  previousMacd: number | null;
  previousMacdSignal: number | null;
  previousClose: number | null;
};

export type Strategy = {
  id: StrategyId;
  name: string;
  rules: string;
  onBar: (bar: StrategyBar, position: PositionSide | "flat") => Decision;
};

export type Sizing =
  | { mode: "fixed"; lots: number }
  | { mode: "risk"; riskPercent: number };

export type OppositePolicy = "reverse" | "close" | "ignore";

/** EMA 20/50/200 entry. `continuous` is the original rule and remains the default. */
export type TrendEntry = "continuous" | "transition";

export type ExitReason = "stop" | "target" | "signal" | "gap";

export type ClosedTrade = {
  id: number;
  side: PositionSide;
  signalTimeMs: number;
  entryTimeMs: number;
  exitTimeMs: number;
  entryFill: number;
  exitFill: number;
  lots: number;
  units: number;
  stopLossPips: number;
  takeProfitPips: number | null;
  /** Net cash after spread, slippage, and commission. */
  pnl: number;
  /** Bid-to-bid cash before spread, slippage, and commission. */
  pricePnl: number;
  /** Cash difference between bid references and fills. Spread and slippage. */
  executionCost: number;
  commission: number;
  /** executionCost + commission. */
  cost: number;
  /** Expected cash loss of a normal stop, including that side's spread, slippage, and both commissions. A gap can lose more. */
  initialRisk: number;
  reason: ExitReason;
};

export type OpenTradeSnapshot = {
  side: PositionSide;
  signalTimeMs: number;
  entryTimeMs: number;
  entryFill: number;
  lots: number;
  unrealizedPnl: number;
};

export type CurvePoint = {
  timeMs: number;
  balance: number;
  equity: number;
};

export type BacktestMetrics = {
  totalTrades: number;
  winningTrades: number;
  losingTrades: number;
  breakevenTrades: number;
  winRate: number | null;
  netProfit: number;
  /** Sum of positive bid-to-bid results, before spread, slippage, and commission. */
  grossProfit: number;
  /** Sum of absolute negative bid-to-bid results, before spread, slippage, and commission. */
  grossLoss: number;
  /** grossProfit / grossLoss using the before-cost bid-to-bid figures. Null when gross loss is 0. */
  profitFactor: number | null;
  executionCosts: number;
  commission: number;
  averageWin: number | null;
  averageLoss: number | null;
  maxDrawdown: number;
  maxDrawdownPercent: number | null;
  expectancy: number | null;
  averageRiskReward: number | null;
  maxConsecutiveWins: number;
  maxConsecutiveLosses: number;
  totalCosts: number;
};

export type SimulationSettings = {
  initialBalance: number;
  accountCurrency: string;
  quoteToAccountRate: number;
  pair: ForexPair;
  sizing: Sizing;
  stopLossPips: number;
  takeProfitPips: number | null;
  spreadPips: number;
  slippagePips: number;
  commissionPerSidePerLot: number;
  maxPositions: number;
  minLots: number;
  maxLots: number;
  onOpposite: OppositePolicy;
  trendEntry: TrendEntry;
  /** Set when the user passed rr. Null when take profit came from tp or the default. */
  rewardRatio: number | null;
  durationMs: number;
  rsiOversold: number;
  rsiOverbought: number;
};

export type BacktestResult = {
  ok: true;
  symbol: string;
  strategyId: StrategyId;
  strategyName: string;
  rules: string;
  timeframe: TimeframeId;
  startMs: number;
  endMs: number;
  candlesUsed: number;
  initialBalance: number;
  finalBalance: number;
  finalEquity: number;
  accountCurrency: string;
  settings: SimulationSettings;
  metrics: BacktestMetrics;
  trades: readonly ClosedTrade[];
  openTrades: readonly OpenTradeSnapshot[];
  equityCurve: readonly CurvePoint[];
  balanceCurve: readonly CurvePoint[];
  assumptions: readonly string[];
  warnings: readonly string[];
  skippedEntries: number;
  label: "in_sample" | "out_of_sample" | "full";
};

export type WalkForwardFold = {
  fold: number;
  inSample: BacktestResult;
  outOfSample: BacktestResult;
};
