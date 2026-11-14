import { logger } from "../config/logger.js";
import type { ChatStore, UsageEntry } from "../db/store.js";
import { estimateMessagesTokens } from "./estimate.js";
import { utcDayStart } from "./utc.js";

export type BudgetLimits = {
  dailyGlobalTokens: number;
  dailyChatTokens: number;
  maxOutputTokens: number;
};

export type BudgetKind = "chat" | "global";

export type ReserveResult =
  | {
      ok: true;
      reservationId: number;
      reservedTokens: number;
      maxOutputTokens: number;
    }
  | { ok: false; kind: BudgetKind };

type Reservation = {
  chatId: number;
  tokens: number;
  dayStart: number;
};

class Mutex {
  private queue: Promise<void> = Promise.resolve();

  async run<T>(fn: () => T | Promise<T>): Promise<T> {
    const previous = this.queue;
    let unlock = (): void => {};
    this.queue = new Promise<void>((resolve) => {
      unlock = resolve;
    });
    await previous;
    try {
      return await fn();
    } finally {
      unlock();
    }
  }
}

export class TokenBudget {
  private nextId = 1;
  private readonly reservations = new Map<number, Reservation>();
  private readonly mutex = new Mutex();

  constructor(
    private readonly store: ChatStore,
    readonly limits: BudgetLimits,
    private readonly now: () => number = Date.now,
  ) {}

  async reserve(
    chatId: number,
    messages: readonly { content: string }[],
  ): Promise<ReserveResult> {
    const estimatedInputTokens = estimateMessagesTokens(messages);
    return await this.mutex.run(() => this.reserveLocked(chatId, estimatedInputTokens));
  }

  async settle(reservationId: number, entry: UsageEntry): Promise<void> {
    await this.mutex.run(() => {
      try {
        this.store.recordUsage(entry);
      } catch (error: unknown) {
        logger.error({ err: error, chatId: entry.chatId }, "Failed to record AI usage");
      } finally {
        this.reservations.delete(reservationId);
      }
    });
  }

  async release(reservationId: number): Promise<void> {
    await this.mutex.run(() => {
      this.reservations.delete(reservationId);
    });
  }

  private reserveLocked(chatId: number, estimatedInputTokens: number): ReserveResult {
    const dayStart = utcDayStart(this.now());
    this.dropStaleReservations(dayStart);

    const committedGlobal = this.store.getTokenUsageSince(dayStart);
    const committedChat = this.store.getChatTokenUsageSince(chatId, dayStart);
    const reserved = this.reservedTotals(chatId, dayStart);
    const remainingGlobal = this.limits.dailyGlobalTokens - committedGlobal - reserved.global;
    const remainingChat = this.limits.dailyChatTokens - committedChat - reserved.chat;
    const remaining = Math.min(remainingGlobal, remainingChat);
    const minimum = estimatedInputTokens + 1;

    if (remaining < minimum) {
      const kind: BudgetKind = remainingChat <= remainingGlobal ? "chat" : "global";
      logger.info(
        {
          chatId,
          kind,
          remainingChat,
          remainingGlobal,
          estimatedInputTokens,
        },
        "AI budget denied",
      );
      return { ok: false, kind };
    }

    const desired = estimatedInputTokens + this.limits.maxOutputTokens;
    const reservedTokens = Math.min(desired, remaining);
    const maxOutputTokens = Math.max(1, reservedTokens - estimatedInputTokens);
    const reservationId = this.nextId;
    this.nextId += 1;
    this.reservations.set(reservationId, { chatId, tokens: reservedTokens, dayStart });

    logger.info(
      {
        chatId,
        reservationId,
        reservedTokens,
        maxOutputTokens,
        remainingChat: remainingChat - reservedTokens,
        remainingGlobal: remainingGlobal - reservedTokens,
      },
      "AI budget reserved",
    );

    return { ok: true, reservationId, reservedTokens, maxOutputTokens };
  }

  private reservedTotals(chatId: number, dayStart: number): { global: number; chat: number } {
    let global = 0;
    let chat = 0;
    for (const reservation of this.reservations.values()) {
      if (reservation.dayStart !== dayStart) {
        continue;
      }
      global += reservation.tokens;
      if (reservation.chatId === chatId) {
        chat += reservation.tokens;
      }
    }
    return { global, chat };
  }

  private dropStaleReservations(dayStart: number): void {
    for (const [id, reservation] of this.reservations) {
      if (reservation.dayStart !== dayStart) {
        this.reservations.delete(id);
      }
    }
  }
}
