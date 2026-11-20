import { formatUtcTimestamp } from "../market/nativeCandles.js";
import type { BacktestResult, WalkForwardFold } from "./types.js";

export function formatBacktestReport(
  result: BacktestResult,
  folds: readonly WalkForwardFold[] = [],
): string {
  const metrics = result.metrics;
  const settings = result.settings;
  const pipValue = settings.pair.unitsPerLot * settings.pair.pipSize * settings.quoteToAccountRate;
  const lines = [
    `Tradexa historical backtest: ${result.symbol}`,
    "This is a historical simulation. It is not future performance and no live order was sent.",
    `Symbol: ${result.symbol}`,
    `Strategy: ${result.strategyName} (${result.strategyId})`,
    `Signal timeframe: ${result.timeframe}`,
    `Period: ${formatUtcTimestamp(result.startMs)} to ${formatUtcTimestamp(result.endMs)}`,
    `Closed candles used: ${result.candlesUsed}`,
    `Initial balance: ${money(result.initialBalance)} ${result.accountCurrency}`,
    `Account currency: ${result.accountCurrency}`,
    `Quote-to-account rate: ${settings.quoteToAccountRate}`,
    `Pip value per lot: ${money(pipValue)} ${result.accountCurrency}`,
    sizingLine(settings),
    ...(result.strategyId === "EMA_TREND"
      ? [`EMA trend entry: ${settings.trendEntry}. continuous re-enters while the stack holds. transition enters only when the stack newly becomes valid.`]
      : []),
    `Stop loss: ${settings.stopLossPips} pips from the entry bid open`,
    takeProfitLine(settings),
    `Spread: ${settings.spreadPips} pips, constant. Ask = bid + this spread.`,
    `Slippage: ${settings.slippagePips} pips on entry and on exit`,
    `Commission: ${money(settings.commissionPerSidePerLot)} ${result.accountCurrency} per side per lot`,
    `Final balance: ${money(result.finalBalance)} ${result.accountCurrency}`,
    `Final equity: ${money(result.finalEquity)} ${result.accountCurrency}`,
    `Net profit/loss, realized only: ${money(metrics.netProfit)} ${result.accountCurrency}`,
    `Open profit is not included in net profit. Open positions at the end: ${result.openTrades.length}`,
    `Total closed trades: ${metrics.totalTrades}`,
    `Winning trades after costs: ${metrics.winningTrades}`,
    `Losing trades after costs: ${metrics.losingTrades}`,
    `Win rate after costs: ${percent(metrics.winRate)}`,
    `Gross profit before costs: ${money(metrics.grossProfit)}`,
    `Gross loss before costs: ${money(metrics.grossLoss)}`,
    `Profit factor before costs: ${numberOr(metrics.profitFactor, "undefined because there is no closed bid-to-bid loss")}`,
    `Spread and slippage cost: ${money(metrics.executionCosts)} ${result.accountCurrency}`,
    `Commission total: ${money(metrics.commission)} ${result.accountCurrency}`,
    `Trading costs: ${money(metrics.totalCosts)} ${result.accountCurrency}`,
    `Average win after costs: ${moneyOr(metrics.averageWin)}`,
    `Average loss after costs: ${moneyOr(metrics.averageLoss)}`,
    `Expectancy after costs: ${moneyOr(metrics.expectancy)}`,
    `Average realized R versus the reserved stop: ${numberOr(metrics.averageRiskReward, "n/a")}`,
    `Maximum drawdown, including adverse bid or ask inside each execution bar: ${money(metrics.maxDrawdown)} (${percent(metrics.maxDrawdownPercent)})`,
    `Consecutive wins/losses after costs: ${metrics.maxConsecutiveWins} / ${metrics.maxConsecutiveLosses}`,
    `Skipped entries: ${result.skippedEntries}`,
    "Margin, leverage, swaps, and broker-specific execution are not modeled.",
    "The historical files are Dukascopy bid candles. Ask prices use the constant spread above.",
    "",
    "Rules:",
    result.rules,
    "",
    "Execution assumptions:",
    ...result.assumptions.map((line) => `- ${line}`),
  ];
  if (result.warnings.length > 0) {
    lines.push("", "Data notes:");
    for (const warning of result.warnings) {
      lines.push(`- ${warning}`);
    }
  }
  if (folds.length > 0) {
    lines.push("", "Walk-forward windows use the same settings. Out-of-sample warm-up starts again at each window. These are not optimized results.");
    for (const fold of folds) {
      lines.push(
        `Fold ${fold.fold} in-sample: trades ${fold.inSample.metrics.totalTrades}, net ${money(fold.inSample.metrics.netProfit)}, drawdown ${money(fold.inSample.metrics.maxDrawdown)}.`,
      );
      lines.push(
        `Fold ${fold.fold} out-of-sample: trades ${fold.outOfSample.metrics.totalTrades}, net ${money(fold.outOfSample.metrics.netProfit)}, drawdown ${money(fold.outOfSample.metrics.maxDrawdown)}.`,
      );
    }
  }
  return lines.join("\n");
}

export const BACKTEST_USAGE = [
  "Usage: /backtest EURUSD [strategy] [timeframe] [start] [end] [name=value]",
  "Strategies: EMA_CROSSOVER, EMA_TREND, RSI, MACD, EMA_RSI.",
  "Timeframes: M1, M5, M15, M30, H1, H4, D1.",
  "Dates are UTC calendar dates. The end date is inclusive.",
  "Settings: balance, risk, lots, sl, tp, rr, spread, slippage, commission, rsilow, rsihigh, positions, opposite, rate, currency, walkforward.",
  "Example: /backtest EURUSD EMA_CROSSOVER H1 2024-01-01 2024-03-01 risk=1 sl=30 rr=2 spread=1",
  "This is a historical simulation. It does not place a live order.",
].join("\n");

function sizingLine(settings: BacktestResult["settings"]): string {
  if (settings.sizing.mode === "fixed") {
    return `Position sizing: fixed ${settings.sizing.lots} lots`;
  }
  return `Position sizing: ${settings.sizing.riskPercent}% of balance, reserved for a normal stop including that side's spread, both slippages, and both commissions. A gap can lose more.`;
}

function takeProfitLine(settings: BacktestResult["settings"]): string {
  if (settings.takeProfitPips === null) {
    return "Take profit: none";
  }
  const ratio = settings.stopLossPips > 0 ? settings.takeProfitPips / settings.stopLossPips : null;
  const source = settings.rewardRatio === null
    ? "the take-profit setting or the default of twice the stop"
    : `the rr setting of ${settings.rewardRatio}`;
  return `Take profit: ${settings.takeProfitPips} pips (${numberOr(ratio, "n/a")} times the stop), from ${source}`;
}

function money(value: number): string {
  if (!Number.isFinite(value)) {
    return "n/a";
  }
  return value.toFixed(2);
}

function moneyOr(value: number | null): string {
  return value === null ? "n/a" : money(value);
}

function percent(value: number | null): string {
  if (value === null || !Number.isFinite(value)) {
    return "n/a";
  }
  return `${(value * 100).toFixed(2)}%`;
}

function numberOr(value: number | null, fallback: string): string {
  if (value === null || !Number.isFinite(value)) {
    return fallback;
  }
  return value.toFixed(2);
}
