import { utcDayStart } from "../budget/utc.js";
import type { ChatStore, DailyLossDay, RecordDailyLossResult } from "../db/store.js";

export type RecordLossInput = {
  chatId: number;
  amount: number;
  currency: string;
  messageId: number;
};

export type RecordLossResult =
  | { status: "recorded"; day: DailyLossDay }
  | { status: "duplicate"; day: DailyLossDay }
  | { status: "currency_mismatch"; day: DailyLossDay }
  | { status: "invalid_amount" };

export type DailyLossStatus = {
  chatId: number;
  dayStart: number;
  totalLoss: number;
  currency: string | null;
  maxDailyLoss: number;
  remaining: number;
  killed: boolean;
};

export type CalculateGate =
  | { ok: true }
  | { ok: false; error: "kill_switch" };

function persistKillIfNeeded(
  store: ChatStore,
  day: DailyLossDay,
  maxDailyLoss: number,
): DailyLossDay {
  if (day.killed || day.totalLoss < maxDailyLoss) {
    return day;
  }
  const updated = store.markDailyLossKilled(day.chatId, day.dayStart);
  return updated ?? { ...day, killed: true };
}

export function recordLoss(
  store: ChatStore,
  input: RecordLossInput,
  maxDailyLoss: number,
  nowMs: number,
): RecordLossResult {
  const dayStart = utcDayStart(nowMs);
  let result: RecordDailyLossResult;
  try {
    result = store.recordDailyLoss({
      chatId: input.chatId,
      dayStart,
      amount: input.amount,
      currency: input.currency,
      messageId: input.messageId,
      createdAt: nowMs,
    });
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "";
    if (message.includes("amount must be a positive finite number")) {
      return { status: "invalid_amount" };
    }
    throw error;
  }

  const day = persistKillIfNeeded(store, result.day, maxDailyLoss);
  return { status: result.status, day };
}

export function getDailyLossStatus(
  store: ChatStore,
  chatId: number,
  maxDailyLoss: number,
  nowMs: number,
): DailyLossStatus {
  const dayStart = utcDayStart(nowMs);
  const stored = store.getDailyLossDay(chatId, dayStart);
  const day =
    stored === null ? null : persistKillIfNeeded(store, stored, maxDailyLoss);
  const totalLoss = day?.totalLoss ?? 0;
  const killed = day?.killed ?? false;
  const remaining = killed ? 0 : Math.max(0, maxDailyLoss - totalLoss);

  return {
    chatId,
    dayStart,
    totalLoss,
    currency: day?.currency ?? null,
    maxDailyLoss,
    remaining,
    killed,
  };
}

export function assertCanCalculate(
  store: ChatStore,
  chatId: number,
  maxDailyLoss: number,
  nowMs: number,
): CalculateGate {
  const status = getDailyLossStatus(store, chatId, maxDailyLoss, nowMs);
  if (status.killed) {
    return { ok: false, error: "kill_switch" };
  }
  return { ok: true };
}
