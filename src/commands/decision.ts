import type { CommandContext, Context } from "grammy";
import type { AiProvider } from "../ai/types.js";
import type { TokenBudget } from "../budget/ledger.js";
import { splitTelegramText } from "../chat/split.js";
import { logger } from "../config/logger.js";
import type { ChatStore } from "../db/store.js";
import { formatDecision, formatDecisionHistory } from "../decision/format.js";
import { runDecision, type DecisionRequest } from "../decision/run.js";
import type { RiskLimits } from "../risk/limits.js";
import { DEFAULT_RISK_LIMITS } from "../risk/limits.js";

export const DECISION_USAGE = [
  "Usage: /decision EURUSD",
  "Usage: /decisions",
  "The result is a shadow-mode assessment. It is not a live trading signal and it does not send an order.",
  "Without a verified live quote, the trading classification stays WAIT.",
].join("\n");

export type DecisionRuntime = {
  store?: ChatStore;
  provider?: AiProvider | null;
  budget?: TokenBudget;
  riskLimits?: RiskLimits;
  nowMs?: number;
  root?: string;
  splitLimit?: number;
  chatId?: number;
  analyze?: DecisionRequest["analyze"];
};

export async function buildDecisionReplies(
  argument: string | undefined,
  runtime: DecisionRuntime = {},
): Promise<string[]> {
  const token = argument?.trim() ?? "";
  if (token.length === 0 || token.toLowerCase() === "help") {
    return splitTelegramText(DECISION_USAGE, runtime.splitLimit);
  }
  if (token.toLowerCase() === "history") {
    return history(runtime);
  }
  try {
    const record = await runDecision({
      symbol: token.split(/\s+/)[0] ?? token,
      ...(runtime.nowMs !== undefined ? { nowMs: runtime.nowMs } : {}),
      ...(runtime.root !== undefined ? { root: runtime.root } : {}),
      ...(runtime.store !== undefined ? { store: runtime.store } : {}),
      ...(runtime.provider !== undefined ? { provider: runtime.provider } : {}),
      ...(runtime.budget !== undefined ? { budget: runtime.budget } : {}),
      ...(runtime.chatId !== undefined ? { chatId: runtime.chatId } : {}),
      ...(runtime.analyze !== undefined ? { analyze: runtime.analyze } : {}),
      riskLimits: runtime.riskLimits ?? DEFAULT_RISK_LIMITS,
    });
    return splitTelegramText(formatDecision(record), runtime.splitLimit);
  } catch (error: unknown) {
    logger.error({ err: error, chatId: runtime.chatId ?? null }, "Decision assessment failed");
    return splitTelegramText(
      "WAIT — LIVE MARKET DATA UNAVAILABLE\nThe decision could not be completed. No order was sent and no price was invented.",
      runtime.splitLimit,
    );
  }
}

export async function buildDecisionHistoryReplies(runtime: DecisionRuntime = {}): Promise<string[]> {
  return history(runtime);
}

async function history(runtime: DecisionRuntime): Promise<string[]> {
  if (runtime.store === undefined || runtime.chatId === undefined) {
    return splitTelegramText("Decision history is unavailable for this chat.", runtime.splitLimit);
  }
  try {
    const records = runtime.store.listDecisions(runtime.chatId, 5);
    return splitTelegramText(formatDecisionHistory(records), runtime.splitLimit);
  } catch (error: unknown) {
    logger.error({ err: error, chatId: runtime.chatId }, "Decision history failed");
    return splitTelegramText("Decision history could not be read. No order was sent.", runtime.splitLimit);
  }
}

export async function handleDecision(
  ctx: CommandContext<Context>,
  runtime: DecisionRuntime = {},
): Promise<void> {
  await reply(ctx, await buildDecisionReplies(typeof ctx.match === "string" ? ctx.match : "", {
    ...runtime,
    ...(ctx.chat?.id !== undefined ? { chatId: ctx.chat.id } : {}),
  }));
}

export async function handleDecisionHistory(
  ctx: CommandContext<Context>,
  runtime: DecisionRuntime = {},
): Promise<void> {
  await reply(ctx, await buildDecisionHistoryReplies({
    ...runtime,
    ...(ctx.chat?.id !== undefined ? { chatId: ctx.chat.id } : {}),
  }));
}

async function reply(ctx: CommandContext<Context>, parts: string[]): Promise<void> {
  for (const part of parts) {
    await ctx.reply(part);
  }
}
