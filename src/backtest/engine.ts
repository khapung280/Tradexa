import { ema, macd, rsi } from "../analysis/indicators.js";
import { classifyCandleGap, type GapClass } from "../analysis/gaps.js";
import { EXECUTION_ASSUMPTIONS } from "./assumptions.js";
import { calculateMetrics } from "./metrics.js";
import { cleanStopPips, pipValuePerLot, positionLots } from "./sizing.js";
import type {
  BacktestCandle,
  BacktestResult,
  ClosedTrade,
  CurvePoint,
  Decision,
  ExitReason,
  OpenTradeSnapshot,
  PositionSide,
  SimulationSettings,
  Strategy,
  StrategyBar,
} from "./types.js";
import type { TimeframeId } from "../market/timeframes.js";

type WorkingPosition = {
  id: number;
  side: PositionSide;
  signalTimeMs: number;
  entryTimeMs: number;
  entryBid: number;
  entryFill: number;
  lots: number;
  units: number;
  stopBid: number;
  targetBid: number | null;
  stopLossPips: number;
  takeProfitPips: number | null;
  entryCommission: number;
  initialRisk: number;
};

type Pending = {
  decision: Exclude<Decision, "hold">;
  signalTimeMs: number;
};

export type SimulationInput = {
  candles: readonly BacktestCandle[];
  m1: readonly BacktestCandle[] | null;
  settings: SimulationSettings;
  strategy: Strategy;
  timeframe: TimeframeId;
  startMs: number;
  endMs: number;
  label?: BacktestResult["label"];
  gapClassifier?: (previousTimeMs: number, currentTimeMs: number, durationMs: number) => GapClass;
};

export function simulate(input: SimulationInput): BacktestResult {
  assertOrdered(input.candles, "Signal candles");
  if (input.m1 !== null) {
    assertOrdered(input.m1, "M1 candles");
  }
  const settings = input.settings;
  const pip = settings.pair.pipSize;
  const rate = settings.quoteToAccountRate;
  const warnings: string[] = [];
  let incompleteM1 = false;
  const cash = { balance: settings.initialBalance };
  let nextId = 1;
  let skipped = 0;
  const closed: ClosedTrade[] = [];
  const equityCurve: CurvePoint[] = [];
  const balanceCurve: CurvePoint[] = [];
  let peak = cash.balance;
  let maxDrawdown = 0;
  let maxDrawdownPercent = 0;
  const sampleEvery = input.candles.length <= 50_000 ? 1 : Math.ceil(input.candles.length / 2_000);
  let marks = 0;
  const positions: WorkingPosition[] = [];
  const segments = splitSegments(input.candles, settings.durationMs, input.gapClassifier ?? classifyCandleGap);
  if (segments.length > 1) {
    warnings.push(
      `${segments.length - 1} unclassified or unexpected gap(s) reset indicator warm-up. No candles were invented.`,
    );
  }

  const noteDrawdown = (equity: number): void => {
    if (equity > peak) {
      peak = equity;
    }
    const amount = peak - equity;
    if (amount > maxDrawdown) {
      maxDrawdown = amount;
    }
    if (peak > 0 && amount / peak > maxDrawdownPercent) {
      maxDrawdownPercent = amount / peak;
    }
  };

  const mark = (timeMs: number, equity: number): void => {
    noteDrawdown(equity);
    marks += 1;
    if (marks % sampleEvery === 0) {
      equityCurve.push({ timeMs, balance: cash.balance, equity });
      balanceCurve.push({ timeMs, balance: cash.balance, equity: cash.balance });
    }
  };

  mark(input.startMs, cash.balance);

  const closePosition = (
    position: WorkingPosition,
    exitBid: number,
    timeMs: number,
    reason: ExitReason,
  ): void => {
    const exitFill = exitPrice(position.side, exitBid, pip, settings);
    const priceDelta = position.side === "buy"
      ? exitFill - position.entryFill
      : position.entryFill - exitFill;
    const exitCommission = settings.commissionPerSidePerLot * position.lots;
    const commission = position.entryCommission + exitCommission;
    const bidDelta = position.side === "buy"
      ? exitBid - position.entryBid
      : position.entryBid - exitBid;
    const pricePnl = bidDelta * position.units * rate;
    const executionCost = Math.abs(position.entryFill - position.entryBid) * position.units * rate
      + Math.abs(exitFill - exitBid) * position.units * rate;
    const pnl = priceDelta * position.units * rate - commission;
    const cost = executionCost + commission;
    cash.balance += priceDelta * position.units * rate - exitCommission;
    closed.push({
      id: position.id,
      side: position.side,
      signalTimeMs: position.signalTimeMs,
      entryTimeMs: position.entryTimeMs,
      exitTimeMs: timeMs,
      entryFill: position.entryFill,
      exitFill,
      lots: position.lots,
      units: position.units,
      stopLossPips: position.stopLossPips,
      takeProfitPips: position.takeProfitPips,
      pnl,
      pricePnl,
      executionCost,
      commission,
      cost,
      initialRisk: position.initialRisk,
      reason,
    });
  };

  for (let segmentIndex = 0; segmentIndex < segments.length; segmentIndex += 1) {
    const segment = segments[segmentIndex];
    if (segment === undefined || segment.length === 0) {
      continue;
    }
    if (segmentIndex > 0 && positions.length > 0) {
      const first = segment[0];
      if (first !== undefined) {
        for (const position of positions.splice(0)) {
          closePosition(position, first.open, first.timeMs, "gap");
        }
      }
    }
    const closes = segment.map((candle) => candle.close);
    const ema20 = ema(closes, 20);
    const ema50 = ema(closes, 50);
    const ema200 = ema(closes, 200);
    const rsi14 = rsi(closes, 14);
    const macdPoints = macd(closes, 12, 26, 9);
    let pending: Pending | null = null;

    for (let index = 0; index < segment.length; index += 1) {
      const candle = segment[index];
      if (candle === undefined) {
        continue;
      }
      const next = segment[index + 1];
      const barEnd = next?.timeMs ?? candle.timeMs + settings.durationMs;

      if (pending !== null) {
        applyPending(
          pending,
          candle,
          positions,
          settings,
          pip,
          rate,
          () => {
            nextId += 1;
            return nextId - 1;
          },
          () => {
            skipped += 1;
          },
          closePosition,
          cash,
        );
        pending = null;
      }

      if (positions.length > 0) {
        const path = executionPath(candle, barEnd, input.m1, settings.durationMs);
        const spreadPrice = settings.spreadPips * pip;
        if (path.incomplete && !incompleteM1) {
          incompleteM1 = true;
          warnings.push(
            "Some bars did not have a complete M1 path. Those bars used the parent high and low, and a bar that touched both stop and target filled the stop.",
          );
        }
        for (const executionBar of path.bars) {
          if (positions.length === 0) {
            break;
          }
          noteDrawdown(adverseEquity(positions, executionBar, spreadPrice, cash.balance, pip, settings, rate));
          for (let positionIndex = positions.length - 1; positionIndex >= 0; positionIndex -= 1) {
            const position = positions[positionIndex];
            if (position === undefined) {
              continue;
            }
            const hit = resolveExit(position, [executionBar], spreadPrice);
            if (hit !== null) {
              closePosition(position, hit.bid, hit.timeMs, hit.reason);
              positions.splice(positionIndex, 1);
            }
          }
        }
      }

      const bar = strategyBar(segment, index, ema20, ema50, ema200, rsi14, macdPoints);
      const side = positions[0]?.side ?? "flat";
      const decision = input.strategy.onBar(bar, side);
      if (decision !== "hold" && next !== undefined) {
        pending = { decision, signalTimeMs: candle.timeMs };
      }
      mark(candle.timeMs, equityOf(cash.balance, positions, candle.close, pip, settings, rate));
    }
  }

  const last = input.candles[input.candles.length - 1];
  const openTrades: OpenTradeSnapshot[] = last === undefined
    ? []
    : positions.map((position) => ({
      side: position.side,
      signalTimeMs: position.signalTimeMs,
      entryTimeMs: position.entryTimeMs,
      entryFill: position.entryFill,
      lots: position.lots,
      unrealizedPnl: unrealized(position, last.close, pip, settings, rate),
    }));
  if (openTrades.length > 0) {
    warnings.push("A trade was still open at the end. It is not counted as closed profit.");
  }
  if (last !== undefined && equityCurve[equityCurve.length - 1]?.timeMs !== last.timeMs) {
    const equity = equityOf(cash.balance, positions, last.close, pip, settings, rate);
    equityCurve.push({ timeMs: last.timeMs, balance: cash.balance, equity });
    balanceCurve.push({ timeMs: last.timeMs, balance: cash.balance, equity: cash.balance });
  }

  const metrics = calculateMetrics(closed, equityCurve);
  metrics.maxDrawdown = maxDrawdown;
  metrics.maxDrawdownPercent = peak > 0 ? maxDrawdownPercent : null;
  const finalEquity = last === undefined
    ? cash.balance
    : equityOf(cash.balance, positions, last.close, pip, settings, rate);

  return {
    ok: true,
    symbol: settings.pair.symbol,
    strategyId: input.strategy.id,
    strategyName: input.strategy.name,
    rules: input.strategy.rules,
    timeframe: input.timeframe,
    startMs: input.startMs,
    endMs: input.endMs,
    candlesUsed: input.candles.length,
    initialBalance: settings.initialBalance,
    finalBalance: cash.balance,
    finalEquity,
    accountCurrency: settings.accountCurrency,
    settings,
    metrics,
    trades: closed,
    openTrades,
    equityCurve,
    balanceCurve,
    assumptions: EXECUTION_ASSUMPTIONS,
    warnings,
    skippedEntries: skipped,
    label: input.label ?? "full",
  };
}

function applyPending(
  pending: Pending,
  candle: BacktestCandle,
  positions: WorkingPosition[],
  settings: SimulationSettings,
  pip: number,
  rate: number,
  takeId: () => number,
  skip: () => void,
  closePosition: (position: WorkingPosition, exitBid: number, timeMs: number, reason: ExitReason) => void,
  cash: { balance: number },
): void {
  const decision = pending.decision;
  if (decision === "flat") {
    for (const position of positions.splice(0)) {
      closePosition(position, candle.open, candle.timeMs, "signal");
    }
    return;
  }
  const opposite = positions.filter((position) => position.side !== decision);
  if (opposite.length > 0) {
    if (settings.onOpposite === "ignore") {
      return;
    }
    for (const position of opposite) {
      closePosition(position, candle.open, candle.timeMs, "signal");
    }
    const remaining = positions.filter((position) => position.side === decision);
    positions.splice(0, positions.length, ...remaining);
    if (settings.onOpposite === "close") {
      return;
    }
  }
  if (positions.length >= settings.maxPositions) {
    return;
  }
  const perLot = pipValuePerLot(settings.pair, rate);
  const sized = positionLots({
    balance: cash.balance,
    sizing: settings.sizing,
    stopLossPips: settings.stopLossPips,
    pipValuePerLot: perLot,
    minLots: settings.minLots,
    maxLots: settings.maxLots,
    side: decision,
    spreadPips: settings.spreadPips,
    slippagePips: settings.slippagePips,
    commissionPerSidePerLot: settings.commissionPerSidePerLot,
  });
  if (!sized.ok) {
    skip();
    return;
  }
  const position = buildPosition(
    takeId(),
    decision,
    candle,
    pending.signalTimeMs,
    sized.lots,
    settings,
    pip,
    rate,
    perLot,
  );
  cash.balance -= position.entryCommission;
  positions.push(position);
}

function buildPosition(
  id: number,
  side: PositionSide,
  candle: BacktestCandle,
  signalTimeMs: number,
  lots: number,
  settings: SimulationSettings,
  pip: number,
  rate: number,
  pipValue: number,
): WorkingPosition {
  const entryFill = side === "buy"
    ? candle.open + (settings.spreadPips + settings.slippagePips) * pip
    : candle.open - settings.slippagePips * pip;
  const stopBid = side === "buy"
    ? candle.open - settings.stopLossPips * pip
    : candle.open + settings.stopLossPips * pip;
  const targetBid = settings.takeProfitPips === null
    ? null
    : side === "buy"
      ? candle.open + settings.takeProfitPips * pip
      : candle.open - settings.takeProfitPips * pip;
  return {
    id,
    side,
    signalTimeMs,
    entryTimeMs: candle.timeMs,
    entryBid: candle.open,
    entryFill,
    lots,
    units: lots * settings.pair.unitsPerLot,
    stopBid,
    targetBid,
    stopLossPips: settings.stopLossPips,
    takeProfitPips: settings.takeProfitPips,
    entryCommission: settings.commissionPerSidePerLot * lots,
    initialRisk: lots * (
      cleanStopPips({
        side,
        stopLossPips: settings.stopLossPips,
        spreadPips: settings.spreadPips,
        slippagePips: settings.slippagePips,
      }) * pipValue + settings.commissionPerSidePerLot * 2
    ),
  };
}

function exitPrice(
  side: PositionSide,
  bid: number,
  pip: number,
  settings: SimulationSettings,
): number {
  if (side === "buy") {
    return bid - settings.slippagePips * pip;
  }
  return bid + (settings.spreadPips + settings.slippagePips) * pip;
}

function resolveExit(
  position: WorkingPosition,
  bars: readonly BacktestCandle[],
  spreadPrice: number,
): { bid: number; reason: "stop" | "target"; timeMs: number } | null {
  for (const bar of bars) {
    if (position.side === "buy") {
      const stopHit = bar.open <= position.stopBid || bar.low <= position.stopBid;
      const targetHit = position.targetBid !== null && (bar.open >= position.targetBid || bar.high >= position.targetBid);
      if (stopHit) {
        return { bid: bar.open <= position.stopBid ? bar.open : position.stopBid, reason: "stop", timeMs: bar.timeMs };
      }
      if (targetHit && position.targetBid !== null) {
        return { bid: bar.open >= position.targetBid ? bar.open : position.targetBid, reason: "target", timeMs: bar.timeMs };
      }
    } else {
      // stopBid and targetBid are ask levels. Ask is bid plus the constant spread.
      const askOpen = bar.open + spreadPrice;
      const askHigh = bar.high + spreadPrice;
      const askLow = bar.low + spreadPrice;
      const stopHit = askOpen >= position.stopBid || askHigh >= position.stopBid;
      const targetHit = position.targetBid !== null && (askOpen <= position.targetBid || askLow <= position.targetBid);
      if (stopHit) {
        const gapped = askOpen >= position.stopBid;
        return {
          bid: gapped ? bar.open : position.stopBid - spreadPrice,
          reason: "stop",
          timeMs: bar.timeMs,
        };
      }
      if (targetHit && position.targetBid !== null) {
        const gapped = askOpen <= position.targetBid;
        return {
          bid: gapped ? bar.open : position.targetBid - spreadPrice,
          reason: "target",
          timeMs: bar.timeMs,
        };
      }
    }
  }
  return null;
}

function adverseEquity(
  positions: readonly WorkingPosition[],
  bar: BacktestCandle,
  spreadPrice: number,
  balance: number,
  pip: number,
  settings: SimulationSettings,
  rate: number,
): number {
  let equity = balance;
  for (const position of positions) {
    equity += unrealized(position, adverseMarkBid(position, bar, spreadPrice), pip, settings, rate);
  }
  return equity;
}

function adverseMarkBid(position: WorkingPosition, bar: BacktestCandle, spreadPrice: number): number {
  if (position.side === "buy") {
    if (bar.open <= position.stopBid) {
      return bar.open;
    }
    if (bar.low <= position.stopBid) {
      return position.stopBid;
    }
    return bar.low;
  }
  const askOpen = bar.open + spreadPrice;
  const askHigh = bar.high + spreadPrice;
  if (askOpen >= position.stopBid) {
    return bar.open;
  }
  if (askHigh >= position.stopBid) {
    return position.stopBid - spreadPrice;
  }
  return bar.high;
}

function executionPath(
  candle: BacktestCandle,
  barEnd: number,
  m1: readonly BacktestCandle[] | null,
  durationMs: number,
): { bars: readonly BacktestCandle[]; incomplete: boolean } {
  const expected = Math.round(durationMs / 60_000);
  if (m1 === null || expected <= 1) {
    return { bars: [candle], incomplete: false };
  }
  const bars = sliceM1(m1, candle.timeMs, barEnd);
  if (!isCompleteMinutePath(bars, candle.timeMs, expected) || candle.timeMs + expected * 60_000 > barEnd) {
    return { bars: [candle], incomplete: true };
  }
  return { bars, incomplete: false };
}

function isCompleteMinutePath(bars: readonly BacktestCandle[], start: number, expected: number): boolean {
  if (bars.length !== expected || start % 60_000 !== 0) {
    return false;
  }
  for (let index = 0; index < bars.length; index += 1) {
    const bar = bars[index];
    if (bar === undefined || bar.timeMs !== start + index * 60_000) {
      return false;
    }
  }
  return true;
}

function sliceM1(m1: readonly BacktestCandle[], start: number, end: number): BacktestCandle[] {
  let low = 0;
  let high = m1.length;
  while (low < high) {
    const mid = (low + high) >> 1;
    const time = m1[mid]?.timeMs ?? 0;
    if (time < start) {
      low = mid + 1;
    } else {
      high = mid;
    }
  }
  const bars: BacktestCandle[] = [];
  for (let index = low; index < m1.length; index += 1) {
    const candle = m1[index];
    if (candle === undefined || candle.timeMs >= end) {
      break;
    }
    bars.push(candle);
  }
  return bars;
}

function assertOrdered(candles: readonly BacktestCandle[], label: string): void {
  for (let index = 1; index < candles.length; index += 1) {
    const previous = candles[index - 1];
    const current = candles[index];
    if (previous !== undefined && current !== undefined && current.timeMs <= previous.timeMs) {
      throw new Error(`${label} are not in increasing time order.`);
    }
  }
}

function splitSegments(
  candles: readonly BacktestCandle[],
  durationMs: number,
  classifier: (previousTimeMs: number, currentTimeMs: number, durationMs: number) => GapClass,
): BacktestCandle[][] {
  const segments: BacktestCandle[][] = [];
  let current: BacktestCandle[] = [];
  for (let index = 0; index < candles.length; index += 1) {
    const candle = candles[index];
    const previous = candles[index - 1];
    if (candle === undefined) {
      continue;
    }
    if (previous !== undefined) {
      const missing = Math.round((candle.timeMs - previous.timeMs) / durationMs) - 1;
      if (missing > 0 && classifier(previous.timeMs, candle.timeMs, durationMs) !== "EXPECTED_MARKET_CLOSURE") {
        if (current.length > 0) {
          segments.push(current);
        }
        current = [];
      }
    }
    current.push(candle);
  }
  if (current.length > 0) {
    segments.push(current);
  }
  return segments;
}

function strategyBar(
  candles: readonly BacktestCandle[],
  index: number,
  ema20: readonly (number | null)[],
  ema50: readonly (number | null)[],
  ema200: readonly (number | null)[],
  rsi14: readonly (number | null)[],
  macdPoints: readonly { macd: number | null; signal: number | null }[],
): StrategyBar {
  const candle = candles[index];
  if (candle === undefined) {
    throw new Error("Strategy candle is missing.");
  }
  const point = macdPoints[index];
  const previousPoint = macdPoints[index - 1];
  return {
    index,
    timeMs: candle.timeMs,
    open: candle.open,
    high: candle.high,
    low: candle.low,
    close: candle.close,
    ema20: ema20[index] ?? null,
    ema50: ema50[index] ?? null,
    ema200: ema200[index] ?? null,
    previousEma20: ema20[index - 1] ?? null,
    previousEma50: ema50[index - 1] ?? null,
    previousEma200: ema200[index - 1] ?? null,
    rsi14: rsi14[index] ?? null,
    previousRsi14: rsi14[index - 1] ?? null,
    macd: point?.macd ?? null,
    macdSignal: point?.signal ?? null,
    previousMacd: previousPoint?.macd ?? null,
    previousMacdSignal: previousPoint?.signal ?? null,
    previousClose: candles[index - 1]?.close ?? null,
  };
}

function unrealized(
  position: WorkingPosition,
  bid: number,
  pip: number,
  settings: SimulationSettings,
  rate: number,
): number {
  const exitFill = exitPrice(position.side, bid, pip, settings);
  const priceDelta = position.side === "buy" ? exitFill - position.entryFill : position.entryFill - exitFill;
  const exitCommission = settings.commissionPerSidePerLot * position.lots;
  return priceDelta * position.units * rate - exitCommission;
}

function equityOf(
  balance: number,
  positions: readonly WorkingPosition[],
  bid: number,
  pip: number,
  settings: SimulationSettings,
  rate: number,
): number {
  let equity = balance;
  for (const position of positions) {
    equity += unrealized(position, bid, pip, settings, rate);
  }
  return equity;
}

