import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { openChatStore, type ChatStore } from "../src/db/store.ts";
import {
  assertCanCalculate,
  getDailyLossStatus,
  recordLoss,
} from "../src/risk/kill.ts";

const MAX = 100;
const CHAT = 42;
const DAY = Date.parse("2026-11-14T12:00:00.000Z");
const DAY_START = Date.parse("2026-11-14T00:00:00.000Z");
const NEXT_DAY = Date.parse("2026-11-15T00:30:00.000Z");
const NEXT_DAY_START = Date.parse("2026-11-15T00:00:00.000Z");

function createTempDir(): string {
  return mkdtempSync(join(tmpdir(), "tradexa-kill-"));
}

function loss(
  store: ChatStore,
  amount: number,
  messageId: number,
  nowMs: number = DAY,
  chatId: number = CHAT,
) {
  return recordLoss(
    store,
    { chatId, amount, currency: "USD", messageId },
    MAX,
    nowMs,
  );
}

describe("daily loss kill switch", () => {
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

  it("activates exactly when total loss reaches the limit", () => {
    expect(loss(store, 40, 1).status).toBe("recorded");
    expect(assertCanCalculate(store, CHAT, MAX, DAY)).toEqual({ ok: true });

    const atLimit = loss(store, 60, 2);
    expect(atLimit.status).toBe("recorded");
    if (atLimit.status !== "recorded") {
      return;
    }
    expect(atLimit.day.totalLoss).toBe(100);
    expect(atLimit.day.killed).toBe(true);
    expect(atLimit.day.dayStart).toBe(DAY_START);
    expect(assertCanCalculate(store, CHAT, MAX, DAY)).toEqual({
      ok: false,
      error: "kill_switch",
    });
    expect(getDailyLossStatus(store, CHAT, MAX, DAY)).toMatchObject({
      totalLoss: 100,
      remaining: 0,
      killed: true,
      currency: "USD",
      maxDailyLoss: MAX,
    });
  });

  it("activates when total loss is above the limit", () => {
    loss(store, 40, 1);
    const above = loss(store, 70, 2);
    expect(above.status).toBe("recorded");
    if (above.status !== "recorded") {
      return;
    }
    expect(above.day.totalLoss).toBe(110);
    expect(above.day.killed).toBe(true);
    expect(assertCanCalculate(store, CHAT, MAX, DAY)).toEqual({
      ok: false,
      error: "kill_switch",
    });
  });

  it("stays active after additional losses and still records them", () => {
    loss(store, 100, 1);
    expect(assertCanCalculate(store, CHAT, MAX, DAY).ok).toBe(false);

    const extra = loss(store, 15, 2);
    expect(extra.status).toBe("recorded");
    if (extra.status !== "recorded") {
      return;
    }
    expect(extra.day.totalLoss).toBe(115);
    expect(extra.day.killed).toBe(true);
    expect(assertCanCalculate(store, CHAT, MAX, DAY)).toEqual({
      ok: false,
      error: "kill_switch",
    });
  });

  it("starts a new UTC day with zero loss and an inactive kill switch", () => {
    loss(store, 100, 1, DAY);
    expect(assertCanCalculate(store, CHAT, MAX, DAY).ok).toBe(false);

    expect(getDailyLossStatus(store, CHAT, MAX, NEXT_DAY)).toMatchObject({
      dayStart: NEXT_DAY_START,
      totalLoss: 0,
      remaining: MAX,
      killed: false,
      currency: null,
    });
    expect(assertCanCalculate(store, CHAT, MAX, NEXT_DAY)).toEqual({ ok: true });
    expect(getDailyLossStatus(store, CHAT, MAX, DAY)).toMatchObject({
      dayStart: DAY_START,
      totalLoss: 100,
      killed: true,
    });
  });

  it("keeps different chats isolated", () => {
    loss(store, 100, 1, DAY, 1);
    loss(store, 20, 1, DAY, 2);

    expect(assertCanCalculate(store, 1, MAX, DAY)).toEqual({
      ok: false,
      error: "kill_switch",
    });
    expect(assertCanCalculate(store, 2, MAX, DAY)).toEqual({ ok: true });
    expect(getDailyLossStatus(store, 2, MAX, DAY).totalLoss).toBe(20);
  });

  it("survives reopening the database", () => {
    loss(store, 100, 7);
    store.close();
    store = openChatStore(dbPath);

    expect(getDailyLossStatus(store, CHAT, MAX, DAY)).toMatchObject({
      totalLoss: 100,
      killed: true,
      remaining: 0,
    });
    expect(assertCanCalculate(store, CHAT, MAX, DAY)).toEqual({
      ok: false,
      error: "kill_switch",
    });
  });

  it("returns invalid_amount for zero, negative, and non-finite values", () => {
    expect(loss(store, 0, 1)).toEqual({ status: "invalid_amount" });
    expect(loss(store, -5, 2)).toEqual({ status: "invalid_amount" });
    expect(loss(store, Number.NaN, 3)).toEqual({ status: "invalid_amount" });
    expect(getDailyLossStatus(store, CHAT, MAX, DAY).totalLoss).toBe(0);
    expect(assertCanCalculate(store, CHAT, MAX, DAY)).toEqual({ ok: true });
  });
});
