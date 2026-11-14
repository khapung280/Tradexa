import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";

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

  constructor(dbPath: string = DEFAULT_DATABASE_PATH) {
    this.path = dbPath;
    ensureParentDirectory(dbPath);
    this.db = new DatabaseSync(dbPath);
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

  close(): void {
    if (this.db.isOpen) {
      this.db.close();
    }
  }
}

export function openChatStore(dbPath: string = DEFAULT_DATABASE_PATH): ChatStore {
  return new ChatStore(dbPath);
}
