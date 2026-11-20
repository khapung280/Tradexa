import type { BacktestMetrics, ClosedTrade, CurvePoint } from "./types.js";

export function calculateMetrics(
  trades: readonly ClosedTrade[],
  points: readonly CurvePoint[],
): BacktestMetrics {
  let wins = 0;
  let losses = 0;
  let breakeven = 0;
  let grossProfit = 0;
  let grossLoss = 0;
  let net = 0;
  let costs = 0;
  let executionCosts = 0;
  let commission = 0;
  let winSum = 0;
  let lossSum = 0;
  let rewardSum = 0;
  let rewardCount = 0;
  let consecutiveWins = 0;
  let consecutiveLosses = 0;
  let maxWins = 0;
  let maxLosses = 0;

  for (const trade of trades) {
    net += trade.pnl;
    costs += trade.cost;
    executionCosts += trade.executionCost;
    commission += trade.commission;
    if (trade.pricePnl > 0) {
      grossProfit += trade.pricePnl;
    } else if (trade.pricePnl < 0) {
      grossLoss += -trade.pricePnl;
    }
    if (trade.pnl > 0) {
      wins += 1;
      winSum += trade.pnl;
      consecutiveWins += 1;
      consecutiveLosses = 0;
    } else if (trade.pnl < 0) {
      losses += 1;
      lossSum += -trade.pnl;
      consecutiveLosses += 1;
      consecutiveWins = 0;
    } else {
      breakeven += 1;
      consecutiveWins = 0;
      consecutiveLosses = 0;
    }
    if (consecutiveWins > maxWins) {
      maxWins = consecutiveWins;
    }
    if (consecutiveLosses > maxLosses) {
      maxLosses = consecutiveLosses;
    }
    if (trade.initialRisk > 0 && Number.isFinite(trade.pnl)) {
      rewardSum += trade.pnl / trade.initialRisk;
      rewardCount += 1;
    }
  }

  const drawdown = maxDrawdown(points);
  const total = trades.length;
  return {
    totalTrades: total,
    winningTrades: wins,
    losingTrades: losses,
    breakevenTrades: breakeven,
    winRate: total === 0 ? null : wins / total,
    netProfit: net,
    grossProfit,
    grossLoss,
    profitFactor: grossLoss === 0 ? null : grossProfit / grossLoss,
    executionCosts,
    commission,
    averageWin: wins === 0 ? null : winSum / wins,
    averageLoss: losses === 0 ? null : lossSum / losses,
    maxDrawdown: drawdown.amount,
    maxDrawdownPercent: drawdown.percent,
    expectancy: total === 0 ? null : net / total,
    averageRiskReward: rewardCount === 0 ? null : rewardSum / rewardCount,
    maxConsecutiveWins: maxWins,
    maxConsecutiveLosses: maxLosses,
    totalCosts: costs,
  };
}

function maxDrawdown(points: readonly CurvePoint[]): { amount: number; percent: number | null } {
  const first = points[0];
  let peak = first?.equity ?? 0;
  let maxAmount = 0;
  let maxPercent: number | null = peak > 0 ? 0 : null;
  for (const point of points) {
    if (point.equity > peak) {
      peak = point.equity;
    }
    const amount = peak - point.equity;
    if (amount > maxAmount) {
      maxAmount = amount;
    }
    if (peak > 0) {
      const percent = amount / peak;
      if (maxPercent === null || percent > maxPercent) {
        maxPercent = percent;
      }
    }
  }
  return { amount: maxAmount, percent: maxPercent };
}
