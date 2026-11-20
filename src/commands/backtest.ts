import type { CommandContext, Context } from "grammy";
import { parseUtcDate } from "../backtest/dates.js";
import { BACKTEST_USAGE, formatBacktestReport } from "../backtest/format.js";
import { DEFAULT_BACKTEST_END, DEFAULT_BACKTEST_START, runBacktest, type BacktestRequest } from "../backtest/run.js";
import { parseStrategyId } from "../backtest/strategies.js";
import type { OppositePolicy, TrendEntry } from "../backtest/types.js";
import { splitTelegramText } from "../chat/split.js";
import { logger } from "../config/logger.js";
import { findTimeframe } from "../market/timeframes.js";
import { findPair } from "../risk/pairs.js";

export type BacktestRuntime = {
  root?: string;
  m1Root?: string;
  splitLimit?: number;
};

export async function buildBacktestReplies(
  argument?: string,
  runtime: BacktestRuntime = {},
): Promise<string[]> {
  const parsed = parseBacktestArgument(argument ?? "");
  if (!parsed.ok) {
    return splitTelegramText(parsed.reason, runtime.splitLimit);
  }
  const request: BacktestRequest = {
    ...parsed.request,
    ...(runtime.root !== undefined ? { root: runtime.root } : {}),
    ...(runtime.m1Root !== undefined ? { m1Root: runtime.m1Root } : {}),
  };
  const outcome = runBacktest(request);
  if (!outcome.ok) {
    return splitTelegramText(`${outcome.reason}\n\n${BACKTEST_USAGE}`, runtime.splitLimit);
  }
  return splitTelegramText(formatBacktestReport(outcome.result, outcome.folds), runtime.splitLimit);
}

export async function handleBacktest(
  ctx: CommandContext<Context>,
  runtime: BacktestRuntime = {},
): Promise<void> {
  try {
    const argument = typeof ctx.match === "string" ? ctx.match : "";
    const parts = await buildBacktestReplies(argument, runtime);
    for (const part of parts) {
      await ctx.reply(part);
    }
  } catch (error: unknown) {
    logger.error({ err: error, chatId: ctx.chat?.id ?? null }, "Backtest failed");
    await ctx.reply("The historical backtest could not be completed. No prices were estimated and no order was sent.");
  }
}

function parseBacktestArgument(
  argument: string,
): { ok: true; request: BacktestRequest } | { ok: false; reason: string } {
  const tokens = argument.trim().split(/\s+/).filter((token) => token.length > 0);
  if (tokens.length === 0) {
    return { ok: false, reason: BACKTEST_USAGE };
  }
  const symbol = findPair(tokens[0] ?? "");
  if (symbol === undefined) {
    return { ok: false, reason: `${tokens[0] ?? ""} is not a supported instrument.\n\n${BACKTEST_USAGE}` };
  }
  const request: BacktestRequest = { symbol: symbol.symbol };
  let start: string | null = null;
  let end: string | null = null;
  let sawRisk = false;
  let sawLots = false;
  let sawTarget = false;
  let sawReward = false;
  for (const token of tokens.slice(1)) {
    const setting = /^([A-Za-z]+)=(.+)$/.exec(token);
    if (setting !== null) {
      const key = setting[1]?.toLowerCase() ?? "";
      const value = setting[2] ?? "";
      const applied = applySetting(request, key, value, {
        sawRisk,
        sawLots,
        sawTarget,
        sawReward,
      });
      if (!applied.ok) {
        return applied;
      }
      if (key === "risk") {
        sawRisk = true;
      }
      if (key === "lots") {
        sawLots = true;
      }
      if (key === "tp") {
        sawTarget = true;
      }
      if (key === "rr") {
        sawReward = true;
      }
      continue;
    }
    if (/^\d{4}-\d{2}-\d{2}$/.test(token)) {
      if (start === null) {
        start = token;
      } else if (end === null) {
        end = token;
      } else {
        return { ok: false, reason: "Only a start date and an end date are accepted." };
      }
      continue;
    }
    const strategy = parseStrategyId(token);
    if (strategy !== null && request.strategy === undefined) {
      request.strategy = strategy;
      continue;
    }
    const timeframe = findTimeframe(token);
    if (timeframe !== undefined && request.timeframe === undefined) {
      request.timeframe = timeframe.id;
      continue;
    }
    return { ok: false, reason: `${token} is not a strategy, timeframe, date, or setting.\n\n${BACKTEST_USAGE}` };
  }
  if (start !== null) {
    const parsed = parseUtcDate(start);
    if (parsed === null) {
      return { ok: false, reason: `${start} is not a real UTC calendar date.` };
    }
    request.startMs = parsed;
  } else {
    request.startMs = DEFAULT_BACKTEST_START;
  }
  if (end !== null) {
    const parsed = parseUtcDate(end);
    if (parsed === null) {
      return { ok: false, reason: `${end} is not a real UTC calendar date.` };
    }
    request.endMs = parsed + 24 * 60 * 60_000;
  } else {
    request.endMs = DEFAULT_BACKTEST_END;
  }
  if (request.startMs !== undefined && request.endMs !== undefined && request.endMs <= request.startMs) {
    return { ok: false, reason: "The end date must be on or after the start date." };
  }
  return { ok: true, request };
}

function applySetting(
  request: BacktestRequest,
  key: string,
  value: string,
  seen: { sawRisk: boolean; sawLots: boolean; sawTarget: boolean; sawReward: boolean },
): { ok: true } | { ok: false; reason: string } {
  if (key === "balance") {
    const number = Number(value);
    if (!Number.isFinite(number)) {
      return { ok: false, reason: "balance must be a number." };
    }
    request.initialBalance = number;
    return { ok: true };
  }
  if (key === "risk") {
    if (seen.sawRisk || seen.sawLots) {
      return { ok: false, reason: "Use risk or lots once, not both and not twice." };
    }
    const number = Number(value);
    if (!Number.isFinite(number)) {
      return { ok: false, reason: "risk must be a number." };
    }
    request.sizing = { mode: "risk", riskPercent: number };
    return { ok: true };
  }
  if (key === "lots") {
    if (seen.sawRisk || seen.sawLots) {
      return { ok: false, reason: "Use risk or lots once, not both and not twice." };
    }
    const number = Number(value);
    if (!Number.isFinite(number)) {
      return { ok: false, reason: "lots must be a number." };
    }
    request.sizing = { mode: "fixed", lots: number };
    return { ok: true };
  }
  if (key === "sl") {
    request.stopLossPips = Number(value);
    return { ok: true };
  }
  if (key === "tp") {
    if (seen.sawTarget || seen.sawReward) {
      return { ok: false, reason: "Use tp or rr, not both." };
    }
    request.takeProfitPips = Number(value);
    return { ok: true };
  }
  if (key === "rr") {
    if (seen.sawTarget || seen.sawReward) {
      return { ok: false, reason: "Use tp or rr, not both." };
    }
    request.rewardRatio = Number(value);
    return { ok: true };
  }
  if (key === "spread") {
    request.spreadPips = Number(value);
    return { ok: true };
  }
  if (key === "slippage") {
    request.slippagePips = Number(value);
    return { ok: true };
  }
  if (key === "commission") {
    request.commissionPerSidePerLot = Number(value);
    return { ok: true };
  }
  if (key === "rsilow") {
    request.rsiOversold = Number(value);
    return { ok: true };
  }
  if (key === "rsihigh") {
    request.rsiOverbought = Number(value);
    return { ok: true };
  }
  if (key === "positions") {
    request.maxPositions = Number(value);
    return { ok: true };
  }
  if (key === "opposite") {
    if (value !== "reverse" && value !== "close" && value !== "ignore") {
      return { ok: false, reason: "opposite must be reverse, close, or ignore." };
    }
    request.onOpposite = value as OppositePolicy;
    return { ok: true };
  }
  if (key === "rate") {
    request.quoteToAccountRate = Number(value);
    return { ok: true };
  }
  if (key === "currency") {
    request.accountCurrency = value.toUpperCase();
    return { ok: true };
  }
  if (key === "walkforward") {
    request.walkForwardFolds = Number(value);
    return { ok: true };
  }
  if (key === "trendentry") {
    if (value !== "continuous" && value !== "transition") {
      return { ok: false, reason: "trendentry must be continuous or transition." };
    }
    request.trendEntry = value as TrendEntry;
    return { ok: true };
  }
  return { ok: false, reason: `${key} is not a backtest setting.` };
}
