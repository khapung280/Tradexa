import { findTimeframe, type TimeframeId } from "../market/timeframes.js";
import { DEFAULT_MAX_LOTS, DEFAULT_MAX_RISK_PERCENT } from "../risk/limits.js";
import { simulate } from "./engine.js";
import { loadClosedCandles } from "./load.js";
import { resolveQuoteRate } from "./sizing.js";
import { findStrategy } from "./strategies.js";
import type { BacktestResult, OppositePolicy, SimulationSettings, Sizing, StrategyId, TrendEntry, WalkForwardFold } from "./types.js";
import { walkForward } from "./walk.js";

export const DEFAULT_BACKTEST_START = Date.UTC(2023, 10, 16);
export const DEFAULT_BACKTEST_END = Date.UTC(2026, 10, 17);

export type BacktestRequest = {
  symbol?: string;
  strategy?: StrategyId;
  timeframe?: TimeframeId;
  startMs?: number;
  endMs?: number;
  initialBalance?: number;
  accountCurrency?: string;
  quoteToAccountRate?: number | null;
  sizing?: Sizing;
  stopLossPips?: number;
  takeProfitPips?: number | null;
  rewardRatio?: number | null;
  spreadPips?: number;
  slippagePips?: number;
  commissionPerSidePerLot?: number;
  maxPositions?: number;
  onOpposite?: OppositePolicy;
  rsiOversold?: number;
  rsiOverbought?: number;
  trendEntry?: TrendEntry;
  walkForwardFolds?: number | null;
  root?: string;
  m1Root?: string;
};

export function runBacktest(
  request: BacktestRequest = {},
): { ok: true; result: BacktestResult; folds: readonly WalkForwardFold[] } | { ok: false; reason: string } {
  const symbol = request.symbol ?? "EURUSD";
  const strategyId = request.strategy ?? "EMA_CROSSOVER";
  const timeframeId = request.timeframe ?? "H1";
  const timeframe = findTimeframe(timeframeId);
  if (timeframe === undefined) {
    return { ok: false, reason: `${timeframeId} is not a supported timeframe.` };
  }
  if (symbol !== "EURUSD") {
    const known = resolveQuoteRate(symbol, request.accountCurrency ?? "USD", request.quoteToAccountRate ?? null);
    if (!known.ok) {
      return known;
    }
    return {
      ok: false,
      reason: `${symbol} is a known pair, but this installation has no native historical candles for it. No prices were estimated.`,
    };
  }
  const accountCurrency = request.accountCurrency ?? "USD";
  const rated = resolveQuoteRate(symbol, accountCurrency, request.quoteToAccountRate ?? null);
  if (!rated.ok) {
    return rated;
  }
  const startMs = request.startMs ?? DEFAULT_BACKTEST_START;
  const endMs = request.endMs ?? DEFAULT_BACKTEST_END;
  if (!(endMs > startMs)) {
    return { ok: false, reason: "The end of the test must be after the start." };
  }
  const initialBalance = request.initialBalance ?? 10_000;
  if (!Number.isFinite(initialBalance) || initialBalance <= 0) {
    return { ok: false, reason: "Initial balance must be a positive number." };
  }
  const stopLossPips = request.stopLossPips ?? 30;
  if (!Number.isFinite(stopLossPips) || stopLossPips <= 0) {
    return { ok: false, reason: "Stop-loss pips must be a positive number." };
  }
  const rewardProvided = request.rewardRatio !== undefined && request.rewardRatio !== null;
  const targetProvided = request.takeProfitPips !== undefined && request.takeProfitPips !== null;
  if (rewardProvided && targetProvided) {
    return { ok: false, reason: "Use tp or rr, not both. No backtest was run." };
  }
  const rewardRatio = request.rewardRatio ?? null;
  const takeProfitPips = request.takeProfitPips !== undefined
    ? request.takeProfitPips
    : rewardRatio === null
      ? stopLossPips * 2
      : stopLossPips * rewardRatio;
  if (takeProfitPips !== null && (!Number.isFinite(takeProfitPips) || takeProfitPips <= 0)) {
    return { ok: false, reason: "Take-profit pips must be a positive number when a target is used." };
  }
  const sizing = request.sizing ?? { mode: "risk" as const, riskPercent: 1 };
  if (sizing.mode === "risk" && (sizing.riskPercent <= 0 || sizing.riskPercent > DEFAULT_MAX_RISK_PERCENT)) {
    return { ok: false, reason: `Risk percent must be greater than 0 and at most ${DEFAULT_MAX_RISK_PERCENT}.` };
  }
  if (sizing.mode === "fixed" && (sizing.lots <= 0 || sizing.lots > DEFAULT_MAX_LOTS)) {
    return { ok: false, reason: `Fixed lots must be greater than 0 and at most ${DEFAULT_MAX_LOTS}.` };
  }
  const spreadPips = request.spreadPips ?? 1;
  const slippagePips = request.slippagePips ?? 0.2;
  const commission = request.commissionPerSidePerLot ?? 0;
  if (
    !Number.isFinite(spreadPips) ||
    !Number.isFinite(slippagePips) ||
    !Number.isFinite(commission) ||
    spreadPips < 0 ||
    slippagePips < 0 ||
    commission < 0
  ) {
    return { ok: false, reason: "Spread, slippage, and commission must be finite numbers and cannot be negative." };
  }
  if (
    request.walkForwardFolds !== undefined &&
    request.walkForwardFolds !== null &&
    (!Number.isInteger(request.walkForwardFolds) || request.walkForwardFolds < 1 || request.walkForwardFolds > 12)
  ) {
    return { ok: false, reason: "walkforward must be an integer from 1 to 12." };
  }
  const rsiOversold = request.rsiOversold ?? 30;
  const rsiOverbought = request.rsiOverbought ?? 70;
  if (!(rsiOversold > 0) || !(rsiOverbought < 100) || !(rsiOversold < rsiOverbought)) {
    return { ok: false, reason: "RSI thresholds must satisfy 0 < oversold < overbought < 100." };
  }
  const settings: SimulationSettings = {
    initialBalance,
    accountCurrency,
    quoteToAccountRate: rated.rate,
    pair: rated.pair,
    sizing,
    stopLossPips,
    takeProfitPips,
    spreadPips,
    slippagePips,
    commissionPerSidePerLot: commission,
    maxPositions: request.maxPositions ?? 1,
    minLots: 0.01,
    maxLots: DEFAULT_MAX_LOTS,
    onOpposite: request.onOpposite ?? "reverse",
    trendEntry: request.trendEntry ?? "continuous",
    rewardRatio,
    durationMs: timeframe.minutes * 60_000,
    rsiOversold,
    rsiOverbought,
  };
  if (!Number.isInteger(settings.maxPositions) || settings.maxPositions < 1) {
    return { ok: false, reason: "Maximum positions must be a positive integer." };
  }
  const loaded = loadClosedCandles({
    timeframe: timeframe.id,
    startMs,
    endMs,
    ...(request.root !== undefined ? { root: request.root } : {}),
    ...(request.m1Root !== undefined ? { m1Root: request.m1Root } : {}),
  });
  if (!loaded.ok) {
    return loaded;
  }
  const strategy = findStrategy(strategyId, rsiOversold, rsiOverbought, request.trendEntry ?? "continuous");
  const simulation = {
    candles: loaded.candles,
    m1: timeframe.id === "M1" ? null : loaded.m1,
    settings,
    strategy,
    timeframe: timeframe.id,
    startMs,
    endMs,
    label: "full" as const,
  };
  let simulated: BacktestResult;
  try {
    simulated = simulate(simulation);
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "The simulation could not run.";
    return { ok: false, reason: message };
  }
  const warnings = [
    ...loaded.warnings,
    ...simulated.warnings,
  ];
  if (commission === 0) {
    warnings.push("Commission is 0 per side per lot because that is the configured value. Spread and slippage are still applied.");
  }
  const result: BacktestResult = { ...simulated, warnings };
  const folds = request.walkForwardFolds === undefined || request.walkForwardFolds === null
    ? []
    : walkForward(simulation, request.walkForwardFolds);
  return { ok: true, result, folds };
}
