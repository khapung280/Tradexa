import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { ChatStore, HISTORY_LIMIT, openChatStore } from "../src/db/store.ts";

function createTempDir(): string {
  return mkdtempSync(join(tmpdir(), "tradexa-store-"));
}

describe("ChatStore", () => {
  let dir: string;
  let dbPath: string;
  let store: ChatStore;

  beforeEach(() => {
    dir = createTempDir();
    dbPath = join(dir, "tradexa.db");
    store = openChatStore(dbPath);
  });

  afterEach(() => {
    store.close();
    rmSync(dir, { recursive: true, force: true });
  });

  it("creates messages, usage, and daily loss tables", () => {
    store.close();
    const db = new DatabaseSync(dbPath);
    try {
      const tables = db
        .prepare(
          `SELECT name FROM sqlite_master
           WHERE type = 'table'
             AND name IN ('messages', 'usage', 'daily_loss_entries', 'daily_loss_days')
           ORDER BY name`,
        )
        .all()
        .map((row) => row.name);
      expect(tables).toEqual([
        "daily_loss_days",
        "daily_loss_entries",
        "messages",
        "usage",
      ]);
    } finally {
      db.close();
    }
  });

  it("keeps chat history isolated by Telegram chat ID", () => {
    store.saveMessage(111, "user", "hello from 111");
    store.saveMessage(111, "assistant", "reply to 111");
    store.saveMessage(222, "user", "hello from 222");

    expect(store.getHistory(111)).toEqual([
      { role: "user", content: "hello from 111" },
      { role: "assistant", content: "reply to 111" },
    ]);
    expect(store.getHistory(222)).toEqual([{ role: "user", content: "hello from 222" }]);
    expect(store.getHistory(-100)).toEqual([]);
  });

  it("returns history in chronological order", () => {
    store.saveMessage(1, "user", "first");
    store.saveMessage(1, "assistant", "second");
    store.saveMessage(1, "user", "third");

    expect(store.getHistory(1).map((message) => message.content)).toEqual([
      "first",
      "second",
      "third",
    ]);
  });

  it("keeps only the latest 20 messages per chat", () => {
    for (let index = 1; index <= HISTORY_LIMIT + 5; index += 1) {
      store.saveMessage(7, "user", `message ${index}`);
    }

    const history = store.getHistory(7);
    expect(history).toHaveLength(HISTORY_LIMIT);
    expect(history[0]?.content).toBe("message 6");
    expect(history[HISTORY_LIMIT - 1]?.content).toBe("message 25");
  });

  it("does not prune another chat when trimming history", () => {
    for (let index = 1; index <= HISTORY_LIMIT + 1; index += 1) {
      store.saveMessage(1, "user", `chat-1 ${index}`);
    }
    store.saveMessage(2, "user", "keep me");

    expect(store.getHistory(1)).toHaveLength(HISTORY_LIMIT);
    expect(store.getHistory(2)).toEqual([{ role: "user", content: "keep me" }]);
  });

  it("records usage per chat without mixing rows", () => {
    store.recordUsage({
      chatId: 11,
      provider: "openai-compatible",
      model: "gemini-2.0-flash",
      inputTokens: 12,
      outputTokens: 4,
    });
    store.recordUsage({
      chatId: 22,
      provider: "openai-compatible",
      model: "gemini-2.0-flash",
      inputTokens: 3,
      outputTokens: 1,
    });
    store.close();

    const db = new DatabaseSync(dbPath);
    try {
      const rows = db
        .prepare(
          "SELECT chat_id, provider, model, input_tokens, output_tokens FROM usage ORDER BY chat_id",
        )
        .all();
      expect(rows).toEqual([
        {
          chat_id: 11,
          provider: "openai-compatible",
          model: "gemini-2.0-flash",
          input_tokens: 12,
          output_tokens: 4,
        },
        {
          chat_id: 22,
          provider: "openai-compatible",
          model: "gemini-2.0-flash",
          input_tokens: 3,
          output_tokens: 1,
        },
      ]);
    } finally {
      db.close();
    }
  });

  it("sums token usage since a UTC timestamp", () => {
    const now = Date.parse("2026-01-16T12:00:00.000Z");
    const today = Date.parse("2026-01-16T01:00:00.000Z");
    const yesterday = Date.parse("2026-01-15T23:00:00.000Z");

    store.recordUsage({
      chatId: 1,
      provider: "openai-compatible",
      model: "gemini-2.0-flash",
      inputTokens: 10,
      outputTokens: 5,
      createdAt: yesterday,
    });
    store.recordUsage({
      chatId: 1,
      provider: "openai-compatible",
      model: "gemini-2.0-flash",
      inputTokens: 7,
      outputTokens: 3,
      createdAt: today,
    });
    store.recordUsage({
      chatId: 2,
      provider: "openai-compatible",
      model: "gemini-2.0-flash",
      inputTokens: 20,
      outputTokens: 1,
      createdAt: today,
    });

    const dayStart = Date.parse("2026-01-16T00:00:00.000Z");
    expect(store.getTokenUsageSince(dayStart)).toBe(31);
    expect(store.getChatTokenUsageSince(1, dayStart)).toBe(10);
    expect(store.getChatTokenUsageSince(2, dayStart)).toBe(21);
    expect(now).toBeGreaterThan(dayStart);
  });

  it("persists history after reopen", () => {
    store.saveMessage(9, "user", "still here");
    store.close();
    store = openChatStore(dbPath);

    expect(store.getHistory(9)).toEqual([{ role: "user", content: "still here" }]);
  });

  it("creates parent directories for the database file", () => {
    store.close();
    const nestedPath = join(dir, "nested", "data", "tradexa.db");
    const nested = openChatStore(nestedPath);
    try {
      nested.saveMessage(1, "user", "nested");
      expect(nested.getHistory(1)).toEqual([{ role: "user", content: "nested" }]);
    } finally {
      nested.close();
    }
  });
});

const DAY = Date.parse("2026-11-14T00:00:00.000Z");

function countLossEntries(dbPath: string, chatId: number): number {
  const db = new DatabaseSync(dbPath);
  try {
    const row = db
      .prepare("SELECT COUNT(*) AS count FROM daily_loss_entries WHERE chat_id = ?")
      .get(chatId);
    const count = row?.count;
    if (typeof count === "number") {
      return count;
    }
    if (typeof count === "bigint") {
      return Number(count);
    }
    return 0;
  } finally {
    db.close();
  }
}

describe("ChatStore daily losses", () => {
  let dir: string;
  let dbPath: string;
  let store: ChatStore;

  beforeEach(() => {
    dir = createTempDir();
    dbPath = join(dir, "tradexa.db");
    store = openChatStore(dbPath);
  });

  afterEach(() => {
    store.close();
    rmSync(dir, { recursive: true, force: true });
  });

  it("keeps separate totals for different chats", () => {
    expect(
      store.recordDailyLoss({
        chatId: 1,
        dayStart: DAY,
        amount: 40,
        currency: "USD",
        messageId: 10,
      }).status,
    ).toBe("recorded");
    expect(
      store.recordDailyLoss({
        chatId: 2,
        dayStart: DAY,
        amount: 75,
        currency: "USD",
        messageId: 10,
      }).status,
    ).toBe("recorded");

    expect(store.getDailyLossDay(1, DAY)).toEqual({
      chatId: 1,
      dayStart: DAY,
      currency: "USD",
      totalLoss: 40,
      killed: false,
    });
    expect(store.getDailyLossDay(2, DAY)).toEqual({
      chatId: 2,
      dayStart: DAY,
      currency: "USD",
      totalLoss: 75,
      killed: false,
    });
  });

  it("does not count a duplicate Telegram message twice", () => {
    const first = store.recordDailyLoss({
      chatId: 3,
      dayStart: DAY,
      amount: 50,
      currency: "usd",
      messageId: 99,
    });
    const second = store.recordDailyLoss({
      chatId: 3,
      dayStart: DAY,
      amount: 50,
      currency: "USD",
      messageId: 99,
    });

    expect(first).toMatchObject({ status: "recorded", day: { totalLoss: 50 } });
    expect(second).toMatchObject({ status: "duplicate", day: { totalLoss: 50 } });
    expect(store.getDailyLossDay(3, DAY)?.totalLoss).toBe(50);
    expect(countLossEntries(dbPath, 3)).toBe(1);
  });

  it("counts two different messages with the same amount", () => {
    store.recordDailyLoss({
      chatId: 4,
      dayStart: DAY,
      amount: 25,
      currency: "USD",
      messageId: 1,
    });
    const result = store.recordDailyLoss({
      chatId: 4,
      dayStart: DAY,
      amount: 25,
      currency: "USD",
      messageId: 2,
    });

    expect(result.status).toBe("recorded");
    expect(store.getDailyLossDay(4, DAY)?.totalLoss).toBe(50);
    expect(countLossEntries(dbPath, 4)).toBe(2);
  });

  it("rejects a different currency for the same chat and UTC day", () => {
    store.recordDailyLoss({
      chatId: 5,
      dayStart: DAY,
      amount: 10,
      currency: "USD",
      messageId: 1,
    });
    const result = store.recordDailyLoss({
      chatId: 5,
      dayStart: DAY,
      amount: 10,
      currency: "EUR",
      messageId: 2,
    });

    expect(result).toMatchObject({
      status: "currency_mismatch",
      day: { currency: "USD", totalLoss: 10 },
    });
    expect(store.getDailyLossDay(5, DAY)?.totalLoss).toBe(10);
    expect(countLossEntries(dbPath, 5)).toBe(1);
  });

  it("rejects zero, negative, non-finite, and profit amounts", () => {
    const base = {
      chatId: 6,
      dayStart: DAY,
      currency: "USD",
      messageId: 1,
    };
    expect(() => store.recordDailyLoss({ ...base, amount: 0 })).toThrow(
      /amount must be a positive finite number/,
    );
    expect(() => store.recordDailyLoss({ ...base, amount: -20 })).toThrow(
      /amount must be a positive finite number/,
    );
    expect(() => store.recordDailyLoss({ ...base, amount: Number.NaN })).toThrow(
      /amount must be a positive finite number/,
    );
    expect(() =>
      store.recordDailyLoss({ ...base, amount: Number.POSITIVE_INFINITY }),
    ).toThrow(/amount must be a positive finite number/);
    expect(store.getDailyLossDay(6, DAY)).toBeNull();
    expect(countLossEntries(dbPath, 6)).toBe(0);
  });

  it("persists daily loss records after reopening the database", () => {
    store.recordDailyLoss({
      chatId: 7,
      dayStart: DAY,
      amount: 60,
      currency: "GBP",
      messageId: 8,
    });
    store.close();
    store = openChatStore(dbPath);

    expect(store.getDailyLossDay(7, DAY)).toEqual({
      chatId: 7,
      dayStart: DAY,
      currency: "GBP",
      totalLoss: 60,
      killed: false,
    });
  });

  it("rolls back a rejected currency so no partial entry is stored", () => {
    store.recordDailyLoss({
      chatId: 8,
      dayStart: DAY,
      amount: 12,
      currency: "USD",
      messageId: 1,
    });
    store.recordDailyLoss({
      chatId: 8,
      dayStart: DAY,
      amount: 99,
      currency: "JPY",
      messageId: 2,
    });

    store.close();
    const db = new DatabaseSync(dbPath);
    try {
      const entries = db
        .prepare(
          "SELECT amount, currency, message_id FROM daily_loss_entries WHERE chat_id = ? ORDER BY message_id",
        )
        .all(8);
      const days = db
        .prepare("SELECT currency, total_loss, killed FROM daily_loss_days WHERE chat_id = ?")
        .all(8);
      expect(entries).toEqual([{ amount: 12, currency: "USD", message_id: 1 }]);
      expect(days).toEqual([{ currency: "USD", total_loss: 12, killed: 0 }]);
    } finally {
      db.close();
    }
  });
});

