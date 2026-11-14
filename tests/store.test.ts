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

  it("creates messages and usage tables", () => {
    store.close();
    const db = new DatabaseSync(dbPath);
    try {
      const tables = db
        .prepare(
          "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('messages', 'usage') ORDER BY name",
        )
        .all()
        .map((row) => row.name);
      expect(tables).toEqual(["messages", "usage"]);
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
