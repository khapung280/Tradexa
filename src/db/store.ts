import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { parseStoredDecision, type DecisionRecord } from "../decision/record.js";

export const DEFAULT_DATABASE_PATH = resolve("data/tradexa.db");
export const HISTORY_LIMIT = 20;

export type ChatRole = "user" | "assistant";

export type StoredMessage = {
  role: ChatRole;
  content: string;
};

export type UsageEntry = {
  chatId: number;
  provider: string;
  model: string;
  inputTokens: number;
  outputTokens: number;
  createdAt?: number;
};

export type DailyLossEntryInput = {
  chatId: number;
  dayStart: number;
  amount: number;
  currency: string;
  messageId: number;
  createdAt?: number;
};

export type DailyLossDay = {
  chatId: number;
  dayStart: number;
  currency: string;
  totalLoss: number;
  killed: boolean;
};

export type RecordDailyLossResult =
  | { status: "recorded"; day: DailyLossDay }
  | { status: "duplicate"; day: DailyLossDay }
  | { status: "currency_mismatch"; day: DailyLossDay };

const SCHEMA = `
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
  content TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS messages_chat_id_created_at_id_idx
  ON messages (chat_id, created_at, id);

CREATE TABLE IF NOT EXISTS usage (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  input_tokens INTEGER NOT NULL,
  output_tokens INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS usage_chat_id_created_at_idx
  ON usage (chat_id, created_at);

CREATE INDEX IF NOT EXISTS usage_created_at_idx
  ON usage (created_at);

CREATE TABLE IF NOT EXISTS daily_loss_entries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id INTEGER NOT NULL,
  day_start INTEGER NOT NULL,
  amount REAL NOT NULL CHECK (amount > 0),
  currency TEXT NOT NULL,
  message_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (chat_id, message_id)
);

CREATE INDEX IF NOT EXISTS daily_loss_entries_chat_id_day_start_idx
  ON daily_loss_entries (chat_id, day_start);

CREATE TABLE IF NOT EXISTS decisions (
  id TEXT PRIMARY KEY,
  chat_id INTEGER,
  symbol TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  schema_version INTEGER NOT NULL,
  fingerprint TEXT NOT NULL,
  payload TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS decisions_chat_id_created_at_idx
  ON decisions (chat_id, created_at);

CREATE TABLE IF NOT EXISTS daily_loss_days (
  chat_id INTEGER NOT NULL,
  day_start INTEGER NOT NULL,
  currency TEXT NOT NULL,
  total_loss REAL NOT NULL,
  killed INTEGER NOT NULL DEFAULT 0 CHECK (killed IN (0, 1)),
  PRIMARY KEY (chat_id, day_start)
);
`;

function isChatRole(value: string): value is ChatRole {
  return value === "user" || value === "assistant";
}

function readTokenSum(row: { tokens?: unknown } | undefined): number {
  const value = row?.tokens;
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "bigint") {
    return Number(value);
  }
  return 0;
}

function readInteger(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "bigint") {
    return Number(value);
  }
  return null;
}

function readDailyLossDay(row: Record<string, unknown> | undefined): DailyLossDay | null {
  if (row === undefined) {
    return null;
  }
  const chatId = readInteger(row.chat_id);
  const dayStart = readInteger(row.day_start);
  const currency = row.currency;
  const totalLoss = row.total_loss;
  const killed = readInteger(row.killed);
  if (
    chatId === null ||
    dayStart === null ||
    typeof currency !== "string" ||
    typeof totalLoss !== "number" ||
    !Number.isFinite(totalLoss) ||
    killed === null
  ) {
    throw new Error("Stored daily loss day is invalid.");
  }
  return {
    chatId,
    dayStart,
    currency,
    totalLoss,
    killed: killed === 1,
  };
}

function isUniqueConstraintError(error: unknown): boolean {
  const code =
    typeof error === "object" && error !== null && "code" in error
      ? String(error.code)
      : "";
  const message = error instanceof Error ? error.message : "";
  return /UNIQUE/i.test(code) || /UNIQUE constraint failed/i.test(message);
}

function normalizeCurrency(raw: string): string {
  return raw.trim().toUpperCase();
}

function ensureParentDirectory(dbPath: string): void {
  if (dbPath === ":memory:") {
    return;
  }
  mkdirSync(dirname(dbPath), { recursive: true });
}

export class ChatStore {
  readonly path: string;
  private readonly db: DatabaseSync;
  private readonly insertMessageStmt;
  private readonly pruneMessagesStmt;
  private readonly listMessagesStmt;
  private readonly insertUsageStmt;
  private readonly globalUsageSinceStmt;
  private readonly chatUsageSinceStmt;
  private readonly insertDailyLossEntryStmt;
  private readonly getDailyLossDayStmt;
  private readonly insertDailyLossDayStmt;
  private readonly addDailyLossTotalStmt;
  private readonly markDailyLossKilledStmt;
  private readonly insertDecisionStmt;
  private readonly getDecisionStmt;
  private readonly listDecisionsStmt;
  private readonly listRecentDecisionsStmt;

  constructor(dbPath: string = DEFAULT_DATABASE_PATH) {
    this.path = dbPath;
    ensureParentDirectory(dbPath);
    this.db = new DatabaseSync(dbPath, { timeout: 5_000 });
    this.db.exec("PRAGMA busy_timeout = 5000");
    if (dbPath !== ":memory:") {
      this.db.exec("PRAGMA journal_mode = WAL");
    }
    this.db.exec(SCHEMA);
    this.insertMessageStmt = this.db.prepare(
      "INSERT INTO messages (chat_id, role, content, created_at) VALUES (?, ?, ?, ?)",
    );
    this.pruneMessagesStmt = this.db.prepare(`
      DELETE FROM messages
      WHERE chat_id = ?
        AND id NOT IN (
          SELECT id FROM messages
          WHERE chat_id = ?
          ORDER BY created_at DESC, id DESC
          LIMIT ?
        )
    `);
    this.listMessagesStmt = this.db.prepare(`
      SELECT role, content FROM (
        SELECT role, content, created_at, id
        FROM messages
        WHERE chat_id = ?
        ORDER BY created_at DESC, id DESC
        LIMIT ?
      )
      ORDER BY created_at ASC, id ASC
    `);
    this.insertUsageStmt = this.db.prepare(
      "INSERT INTO usage (chat_id, provider, model, input_tokens, output_tokens, created_at) VALUES (?, ?, ?, ?, ?, ?)",
    );
    this.globalUsageSinceStmt = this.db.prepare(
      "SELECT COALESCE(SUM(input_tokens + output_tokens), 0) AS tokens FROM usage WHERE created_at >= ?",
    );
    this.chatUsageSinceStmt = this.db.prepare(
      "SELECT COALESCE(SUM(input_tokens + output_tokens), 0) AS tokens FROM usage WHERE chat_id = ? AND created_at >= ?",
    );
    this.insertDailyLossEntryStmt = this.db.prepare(
      "INSERT INTO daily_loss_entries (chat_id, day_start, amount, currency, message_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
    );
    this.getDailyLossDayStmt = this.db.prepare(
      "SELECT chat_id, day_start, currency, total_loss, killed FROM daily_loss_days WHERE chat_id = ? AND day_start = ?",
    );
    this.insertDailyLossDayStmt = this.db.prepare(
      "INSERT INTO daily_loss_days (chat_id, day_start, currency, total_loss, killed) VALUES (?, ?, ?, ?, 0)",
    );
    this.addDailyLossTotalStmt = this.db.prepare(
      "UPDATE daily_loss_days SET total_loss = total_loss + ? WHERE chat_id = ? AND day_start = ?",
    );
    this.markDailyLossKilledStmt = this.db.prepare(
      "UPDATE daily_loss_days SET killed = 1 WHERE chat_id = ? AND day_start = ?",
    );
    this.insertDecisionStmt = this.db.prepare(
      "INSERT INTO decisions (id, chat_id, symbol, created_at, schema_version, fingerprint, payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
    );
    this.getDecisionStmt = this.db.prepare(
      "SELECT payload FROM decisions WHERE id = ?",
    );
    this.listDecisionsStmt = this.db.prepare(
      "SELECT payload FROM decisions WHERE chat_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
    );
    this.listRecentDecisionsStmt = this.db.prepare(
      "SELECT payload FROM decisions ORDER BY created_at DESC, id DESC LIMIT ?",
    );
  }

  saveMessage(chatId: number, role: ChatRole, content: string): void {
    if (!Number.isSafeInteger(chatId)) {
      throw new Error("chatId must be a safe integer.");
    }
    if (!isChatRole(role)) {
      throw new Error("Message role must be user or assistant.");
    }

    this.db.exec("BEGIN IMMEDIATE");
    try {
      this.insertMessageStmt.run(chatId, role, content, Date.now());
      this.pruneMessagesStmt.run(chatId, chatId, HISTORY_LIMIT);
      this.db.exec("COMMIT");
    } catch (error: unknown) {
      this.db.exec("ROLLBACK");
      throw error;
    }
  }

  getHistory(chatId: number): StoredMessage[] {
    if (!Number.isSafeInteger(chatId)) {
      throw new Error("chatId must be a safe integer.");
    }

    const rows = this.listMessagesStmt.all(chatId, HISTORY_LIMIT);
    return rows.map((row) => {
      const role = row.role;
      const content = row.content;
      if (typeof role !== "string" || typeof content !== "string" || !isChatRole(role)) {
        throw new Error("Stored message is invalid.");
      }
      return { role, content };
    });
  }

  recordUsage(entry: UsageEntry): void {
    if (!Number.isSafeInteger(entry.chatId)) {
      throw new Error("chatId must be a safe integer.");
    }
    if (!Number.isSafeInteger(entry.inputTokens) || entry.inputTokens < 0) {
      throw new Error("inputTokens must be a non-negative integer.");
    }
    if (!Number.isSafeInteger(entry.outputTokens) || entry.outputTokens < 0) {
      throw new Error("outputTokens must be a non-negative integer.");
    }

    const createdAt = entry.createdAt ?? Date.now();
    if (!Number.isSafeInteger(createdAt) || createdAt < 0) {
      throw new Error("createdAt must be a non-negative integer.");
    }

    this.insertUsageStmt.run(
      entry.chatId,
      entry.provider,
      entry.model,
      entry.inputTokens,
      entry.outputTokens,
      createdAt,
    );
  }

  getTokenUsageSince(sinceMs: number): number {
    return readTokenSum(this.globalUsageSinceStmt.get(sinceMs));
  }

  getChatTokenUsageSince(chatId: number, sinceMs: number): number {
    if (!Number.isSafeInteger(chatId)) {
      throw new Error("chatId must be a safe integer.");
    }
    return readTokenSum(this.chatUsageSinceStmt.get(chatId, sinceMs));
  }

  getDailyLossDay(chatId: number, dayStart: number): DailyLossDay | null {
    if (!Number.isSafeInteger(chatId)) {
      throw new Error("chatId must be a safe integer.");
    }
    if (!Number.isSafeInteger(dayStart) || dayStart < 0) {
      throw new Error("dayStart must be a non-negative integer.");
    }
    return readDailyLossDay(this.getDailyLossDayStmt.get(chatId, dayStart));
  }

  recordDailyLoss(entry: DailyLossEntryInput): RecordDailyLossResult {
    if (!Number.isSafeInteger(entry.chatId)) {
      throw new Error("chatId must be a safe integer.");
    }
    if (!Number.isSafeInteger(entry.dayStart) || entry.dayStart < 0) {
      throw new Error("dayStart must be a non-negative integer.");
    }
    if (!Number.isSafeInteger(entry.messageId)) {
      throw new Error("messageId must be a safe integer.");
    }
    if (!Number.isFinite(entry.amount) || entry.amount <= 0) {
      throw new Error("amount must be a positive finite number.");
    }

    const currency = normalizeCurrency(entry.currency);
    if (currency.length === 0) {
      throw new Error("currency must be a non-empty string.");
    }

    const createdAt = entry.createdAt ?? Date.now();
    if (!Number.isSafeInteger(createdAt) || createdAt < 0) {
      throw new Error("createdAt must be a non-negative integer.");
    }

    this.db.exec("BEGIN IMMEDIATE");
    try {
      const existing = readDailyLossDay(
        this.getDailyLossDayStmt.get(entry.chatId, entry.dayStart),
      );
      if (existing !== null && existing.currency !== currency) {
        this.db.exec("ROLLBACK");
        return { status: "currency_mismatch", day: existing };
      }

      try {
        this.insertDailyLossEntryStmt.run(
          entry.chatId,
          entry.dayStart,
          entry.amount,
          currency,
          entry.messageId,
          createdAt,
        );
      } catch (error: unknown) {
        this.db.exec("ROLLBACK");
        if (isUniqueConstraintError(error)) {
          const day = this.getDailyLossDay(entry.chatId, entry.dayStart);
          if (day === null) {
            throw error;
          }
          return { status: "duplicate", day };
        }
        throw error;
      }

      if (existing === null) {
        this.insertDailyLossDayStmt.run(
          entry.chatId,
          entry.dayStart,
          currency,
          entry.amount,
        );
      } else {
        this.addDailyLossTotalStmt.run(entry.amount, entry.chatId, entry.dayStart);
      }

      this.db.exec("COMMIT");
    } catch (error: unknown) {
      try {
        this.db.exec("ROLLBACK");
      } catch {
        // Already rolled back (duplicate path) or not in a transaction.
      }
      throw error;
    }

    const day = this.getDailyLossDay(entry.chatId, entry.dayStart);
    if (day === null) {
      throw new Error("Daily loss day is missing after record.");
    }
    return { status: "recorded", day };
  }

  insertDecision(record: DecisionRecord): void {
    const stored = parseStoredDecision(record);
    if (!stored.ok) {
      throw new Error(stored.reason);
    }
    try {
      this.insertDecisionStmt.run(
        stored.record.id,
        stored.record.chatId,
        stored.record.symbol,
        stored.record.createdAtMs,
        stored.record.schemaVersion,
        stored.record.fingerprint,
        JSON.stringify(stored.record),
      );
    } catch (error: unknown) {
      if (isUniqueConstraintError(error)) {
        throw new Error("A decision with this id already exists. Previous decisions are not overwritten.");
      }
      throw error;
    }
  }

  getDecision(id: string): DecisionRecord | null {
    const row = this.getDecisionStmt.get(id) as { payload?: unknown } | undefined;
    if (row === undefined) {
      return null;
    }
    return readDecisionPayload(row.payload);
  }

  listRecentDecisions(limit: number): DecisionRecord[] {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 20) {
      throw new Error("Decision history limit must be an integer from 1 to 20.");
    }
    const rows = this.listRecentDecisionsStmt.all(limit) as { payload?: unknown }[];
    return rows.map((row) => readDecisionPayload(row.payload));
  }

  listDecisions(chatId: number, limit: number): DecisionRecord[] {
    if (!Number.isSafeInteger(chatId)) {
      throw new Error("chatId must be a safe integer.");
    }
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 20) {
      throw new Error("Decision history limit must be an integer from 1 to 20.");
    }
    const rows = this.listDecisionsStmt.all(chatId, limit) as { payload?: unknown }[];
    return rows.map((row) => readDecisionPayload(row.payload));
  }

  markDailyLossKilled(chatId: number, dayStart: number): DailyLossDay | null {
    if (!Number.isSafeInteger(chatId)) {
      throw new Error("chatId must be a safe integer.");
    }
    if (!Number.isSafeInteger(dayStart) || dayStart < 0) {
      throw new Error("dayStart must be a non-negative integer.");
    }
    this.markDailyLossKilledStmt.run(chatId, dayStart);
    return this.getDailyLossDay(chatId, dayStart);
  }

  close(): void {
    if (this.db.isOpen) {
      this.db.close();
    }
  }
}

function readDecisionPayload(payload: unknown): DecisionRecord {
  if (typeof payload !== "string") {
    throw new Error("Stored decision record is invalid.");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(payload);
  } catch {
    throw new Error("Stored decision record is invalid.");
  }
  const stored = parseStoredDecision(parsed);
  if (!stored.ok) {
    throw new Error("Stored decision record is invalid.");
  }
  return stored.record;
}

export function openChatStore(dbPath: string = DEFAULT_DATABASE_PATH): ChatStore {
  return new ChatStore(dbPath);
}
