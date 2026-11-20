import { DatabaseSync } from "node:sqlite";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Worker } from "node:worker_threads";
import { describe, expect, it } from "vitest";
import { openChatStore } from "../src/db/store.ts";

describe("sqlite sharing", () => {
  it("lets a second connection read a committed decision after waiting for a write lock", async () => {
    const dir = mkdtempSync(join(tmpdir(), "tradexa-sqlite-"));
    const path = join(dir, "tradexa.db");
    const writer = openChatStore(path);
    try {
      writer.saveMessage(7, "user", "shared-history");
      const reader = openChatStore(path);
      expect(reader.getHistory(7)).toEqual([{ role: "user", content: "shared-history" }]);
      reader.close();

      const raw = new DatabaseSync(path, { timeout: 5_000 });
      raw.exec("PRAGMA busy_timeout = 5000");
      raw.exec("BEGIN IMMEDIATE");
      const worker = new Worker(`
        const { parentPort, workerData } = require("node:worker_threads");
        const { DatabaseSync } = require("node:sqlite");
        const db = new DatabaseSync(workerData, { timeout: 5000 });
        db.exec("PRAGMA busy_timeout = 5000");
        db.exec("PRAGMA journal_mode = WAL");
        db.exec("INSERT INTO messages (chat_id, role, content, created_at) VALUES (7, 'user', 'from-peer', 1)");
        parentPort.postMessage("inserted");
      `, { eval: true, workerData: path });
      const inserted = new Promise((resolve, reject) => {
        worker.once("message", resolve);
        worker.once("error", reject);
      });
      await new Promise((resolve) => setTimeout(resolve, 200));
      raw.exec("COMMIT");
      raw.close();
      await inserted;
      const after = openChatStore(path);
      expect(after.getHistory(7).map((item) => item.content)).toContain("from-peer");
      after.close();
    } finally {
      writer.close();
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
