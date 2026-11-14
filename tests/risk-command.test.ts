import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { renderAnalyzeReply } from "../src/commands/analyze.ts";
import { renderLearnReply } from "../src/commands/learn.ts";
import { RISK_USAGE, renderRiskReply } from "../src/commands/risk.ts";
import { openChatStore, type ChatStore } from "../src/db/store.ts";
import type { RiskLimits } from "../src/risk/limits.ts";

const LIMITS: RiskLimits = { maxRiskPercent: 2, maxLots: 2, maxDailyLoss: 100 };

const SIGNAL_PATTERNS = [
  /\bbuy now\b/i,
  /\bsell now\b/i,
  /\byou should (buy|sell)\b/i,
  /\benter (a |the )?trade\b/i,
  /\bguaranteed (profit|return|win)\b/i,
];

const POSITION_SIZE_LINE = /Position size:/i;
const COMPUTED_LOTS = /0\.20/;

function expectNoPositionSize(reply: string): void {
  expect(reply).not.toMatch(POSITION_SIZE_LINE);
  expect(reply).not.toMatch(COMPUTED_LOTS);
}

describe("renderRiskReply", () => {
  it("sizes a valid EURUSD request", () => {
    const reply = renderRiskReply("EURUSD 10000 USD 1 50", LIMITS);
    expect(reply).toContain("Pair: EURUSD");
    expect(reply).toContain("Account: 10000 USD");
    expect(reply).toContain("Risk: 1% = 100 USD");
    expect(reply).toContain("Stop-loss: 50 pips");
    expect(reply).toContain("Pip value: 10 USD per standard lot");
    expect(reply).toContain("Conversion rate: 1");
    expect(reply).toContain("Position size: 0.20 standard lots");
    expect(reply).toContain("does not place orders");
    for (const pattern of SIGNAL_PATTERNS) {
      expect(reply, String(pattern)).not.toMatch(pattern);
    }
  });

  it("sizes EURGBP when an explicit conversion rate is provided", () => {
    const reply = renderRiskReply("EURGBP 10000 USD 1 50 1.25", LIMITS);
    expect(reply).toContain("Pair: EURGBP");
    expect(reply).toContain("Pip value: 12.50 USD per standard lot");
    expect(reply).toContain("Conversion rate: 1.25 USD per 1 GBP");
    expect(reply).toContain("Position size: 0.16 standard lots");
  });

  it("explains a missing conversion rate without a position size", () => {
    const reply = renderRiskReply("EURGBP 10000 USD 1 50", LIMITS);
    expect(reply).toMatch(/conversion rate is required/i);
    expect(reply).toMatch(/does not invent exchange rates/i);
    expectNoPositionSize(reply);
  });

  it("explains an invalid conversion rate without a position size", () => {
    const reply = renderRiskReply("EURGBP 10000 USD 1 50 0", LIMITS);
    expect(reply).toMatch(/conversion rate must be a positive number/i);
    expectNoPositionSize(reply);
  });

  it("allows risk percentage below the limit and rejects above it", () => {
    const below = renderRiskReply("EURUSD 10000 USD 1 50", LIMITS);
    expect(below).toContain("Position size: 0.20 standard lots");

    const above = renderRiskReply("EURUSD 10000 USD 3 50", LIMITS);
    expect(above).toMatch(/exceeds the maximum of 2%/);
    expect(above).toMatch(/will not reduce the requested risk/i);
    expectNoPositionSize(above);
  });

  it("allows lot size below the limit and rejects above it", () => {
    const below = renderRiskReply("EURUSD 10000 USD 1 50", LIMITS);
    expect(below).toContain("Position size: 0.20 standard lots");

    const above = renderRiskReply("EURUSD 10000 USD 1 50", {
      maxRiskPercent: 2,
      maxLots: 0.1,
      maxDailyLoss: 100,
    });
    expect(above).toMatch(/exceeds the maximum lot size/i);
    expect(above).toMatch(/will not reduce the position size/i);
    expectNoPositionSize(above);
  });

  it("rejects invalid command arguments without a position size", () => {
    expect(renderRiskReply("", LIMITS)).toBe(RISK_USAGE);
    expect(renderRiskReply("EURUSD 10000 USD 1", LIMITS)).toBe(RISK_USAGE);
    expect(renderRiskReply("EURUSD abc USD 1 50", LIMITS)).toBe(RISK_USAGE);
    expect(renderRiskReply("EURUSD 10000 USD 1 50 1.25 extra", LIMITS)).toBe(RISK_USAGE);
    expectNoPositionSize(RISK_USAGE);
  });

  it("rejects an unknown currency pair without a position size", () => {
    const reply = renderRiskReply("FAKEUSD 10000 USD 1 50", LIMITS);
    expect(reply).toMatch(/FAKEUSD/);
    expect(reply).toMatch(/Unknown pairs are not sized/);
    expectNoPositionSize(reply);
  });
});

const NOW = Date.parse("2026-11-14T15:00:00.000Z");
const NEXT_DAY = Date.parse("2026-11-15T00:30:00.000Z");

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

describe("renderRiskReply kill switch", () => {
  let dir: string;
  let dbPath: string;
  let store: ChatStore;

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), "tradexa-risk-cmd-"));
    dbPath = join(dir, "tradexa.db");
    store = openChatStore(dbPath);
  });

  afterEach(() => {
    store.close();
    rmSync(dir, { recursive: true, force: true });
  });

  function context(chatId: number, messageId: number, nowMs: number = NOW) {
    return { store, chatId, nowMs, messageId };
  }

  it("sizes EURUSD when the kill switch is inactive", () => {
    const reply = renderRiskReply("EURUSD 10000 USD 1 50", LIMITS, context(11, 1));
    expect(reply).toContain("Position size: 0.20 standard lots");
  });

  it("records a loss and shows remaining on status", () => {
    const recorded = renderRiskReply("loss 50 USD", LIMITS, context(11, 1));
    expect(recorded).toMatch(/Recorded a reported loss of 50 USD/);
    expect(recorded).toContain("Today's total: 50 USD");
    expect(recorded).toContain("Remaining: 50");
    expect(recorded).toContain("Kill switch: OFF");
    expect(recorded).toMatch(/user-reported/i);

    const status = renderRiskReply("status", LIMITS, context(11, 2));
    expect(status).toContain("Reported loss: 50 USD");
    expect(status).toContain("Limit: 100");
    expect(status).toContain("Remaining: 50");
    expect(status).toContain("Kill switch: OFF");
  });

  it("does not count a duplicate Telegram message twice", () => {
    renderRiskReply("loss 40 USD", LIMITS, context(11, 9));
    const duplicate = renderRiskReply("loss 40 USD", LIMITS, context(11, 9));
    expect(duplicate).toMatch(/already recorded/i);
    expect(duplicate).toContain("Today's total: 40 USD");
    expect(countLossEntries(dbPath, 11)).toBe(1);
  });

  it("rejects a different currency without adding a record", () => {
    renderRiskReply("loss 20 USD", LIMITS, context(11, 1));
    const mismatch = renderRiskReply("loss 20 EUR", LIMITS, context(11, 2));
    expect(mismatch).toMatch(/are in USD/);
    expect(mismatch).toMatch(/does not convert currencies/i);
    expect(countLossEntries(dbPath, 11)).toBe(1);
  });

  it("creates no database record for invalid loss arguments", () => {
    renderRiskReply("loss 0 USD", LIMITS, context(11, 1));
    renderRiskReply("loss -5 USD", LIMITS, context(11, 2));
    renderRiskReply("loss abc USD", LIMITS, context(11, 3));
    renderRiskReply("loss 50", LIMITS, context(11, 4));
    expect(countLossEntries(dbPath, 11)).toBe(0);
    expect(renderRiskReply("status", LIMITS, context(11, 5))).toContain("Reported loss: 0");
  });

  it("activates the kill switch at the limit and still records further losses", () => {
    renderRiskReply("loss 100 USD", LIMITS, context(11, 1));
    const status = renderRiskReply("status", LIMITS, context(11, 2));
    expect(status).toContain("Reported loss: 100 USD");
    expect(status).toContain("Kill switch: ON");
    expect(status).toContain("Remaining: 0");

    const extra = renderRiskReply("loss 15 USD", LIMITS, context(11, 3));
    expect(extra).toContain("Today's total: 115 USD");
    expect(extra).toContain("Kill switch: ON");
    expect(countLossEntries(dbPath, 11)).toBe(2);

    const blocked = renderRiskReply("EURUSD 10000 USD 1 50", LIMITS, context(11, 4));
    expect(blocked).toMatch(/reported-loss limit/i);
    expectNoPositionSize(blocked);
    expect(blocked).not.toMatch(/\blots\b/i);
  });

  it("starts a new UTC day with zero loss and an inactive kill switch", () => {
    renderRiskReply("loss 100 USD", LIMITS, context(11, 1, NOW));
    expect(renderRiskReply("EURUSD 10000 USD 1 50", LIMITS, context(11, 2, NOW))).toMatch(
      /reported-loss limit/i,
    );

    const status = renderRiskReply("status", LIMITS, context(11, 3, NEXT_DAY));
    expect(status).toContain("Reported loss: 0");
    expect(status).toContain("Kill switch: OFF");
    expect(status).toContain("Remaining: 100");
    expect(renderRiskReply("EURUSD 10000 USD 1 50", LIMITS, context(11, 4, NEXT_DAY))).toContain(
      "Position size: 0.20 standard lots",
    );
  });

  it("does not block another chat when one chat is killed", () => {
    renderRiskReply("loss 100 USD", LIMITS, context(11, 1));
    const blocked = renderRiskReply("EURUSD 10000 USD 1 50", LIMITS, context(11, 2));
    const allowed = renderRiskReply("EURUSD 10000 USD 1 50", LIMITS, context(22, 1));
    expectNoPositionSize(blocked);
    expect(allowed).toContain("Position size: 0.20 standard lots");
  });

  it("keeps /learn and /analyze working when killed", () => {
    renderRiskReply("loss 100 USD", LIMITS, context(11, 1));
    expect(renderRiskReply("EURUSD 10000 USD 1 50", LIMITS, context(11, 2))).toMatch(
      /reported-loss limit/i,
    );
    expect(renderLearnReply("")).toMatch(/Tradexa Forex lessons/i);
    expect(renderAnalyzeReply()).toMatch(/Data: unavailable/);
  });
});


