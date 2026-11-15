import { describe, expect, it } from "vitest";
import { renderHelpReply } from "../src/commands/help.ts";
import { renderSessionReply } from "../src/commands/session.ts";
import { renderStartReply } from "../src/commands/start.ts";
import { renderTimeframeReply } from "../src/commands/timeframe.ts";
import { detectSessionOverlap } from "../src/market/sessionOverlap.ts";
import { detectSession } from "../src/market/sessionState.ts";
import { TRADING_SESSIONS } from "../src/market/sessions.ts";
import { TIMEFRAME_IDS, TIMEFRAMES } from "../src/market/timeframes.ts";

const SIGNAL_PATTERNS = [
  /\bbuy now\b/i,
  /\bsell now\b/i,
  /\byou should (buy|sell)\b/i,
  /\benter (a |the )?trade\b/i,
  /\bguaranteed (profit|return|win)\b/i,
];

function utc(iso: string): number {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) {
    throw new Error(`invalid utc timestamp: ${iso}`);
  }
  return ms;
}

describe("renderTimeframeReply", () => {
  it("lists every supported timeframe from M1 to D1", () => {
    const reply = renderTimeframeReply("");
    expect(TIMEFRAME_IDS).toEqual(["M1", "M5", "M15", "M30", "H1", "H4", "D1"]);
    for (const timeframe of TIMEFRAMES) {
      expect(reply).toContain(`${timeframe.id} — ${timeframe.displayName}`);
    }
    expect(reply).toContain("does not download market data");
  });

  it("shows a single validated timeframe", () => {
    const reply = renderTimeframeReply(" m15 ");
    expect(reply).toContain("M15 — 15 minutes");
    expect(reply).toContain("Duration: 15 minutes");
    expect(reply).not.toContain("H4 — 240 minutes");
  });

  it("rejects invalid timeframe ids and still lists the catalog", () => {
    const reply = renderTimeframeReply("W1");
    expect(reply.startsWith('Unknown timeframe "W1".')).toBe(true);
    expect(reply).toContain("M1 — 1 minute");
    expect(reply).toContain("D1 — 1440 minutes");
  });
});

describe("renderSessionReply", () => {
  it("shows all four sessions using the existing detector", () => {
    const nowMs = utc("2026-01-12T14:00:00.000Z");
    const reply = renderSessionReply(nowMs);
    for (const session of TRADING_SESSIONS) {
      const detection = detectSession(session.id, nowMs);
      expect(detection.ok).toBe(true);
      if (!detection.ok) {
        continue;
      }
      expect(reply).toContain(`${session.displayName} — ${detection.state.toUpperCase()}`);
      expect(reply).toContain(session.timezone);
      expect(reply).toContain(`${session.localOpen}–${session.localClose}`);
      expect(reply).toContain(detection.localTime);
    }
  });

  it("shows London/New York overlap as YES when both are open", () => {
    const nowMs = utc("2026-01-12T14:00:00.000Z");
    const overlap = detectSessionOverlap("london", "newyork", nowMs);
    expect(overlap.ok && overlap.overlapping).toBe(true);
    const reply = renderSessionReply(nowMs);
    expect(reply).toContain("London / New York overlap: YES");
    expect(reply).toContain("London — OPEN");
    expect(reply).toContain("New York — OPEN");
  });

  it("shows London/New York overlap as NO when New York is still closed", () => {
    const nowMs = utc("2026-01-12T10:00:00.000Z");
    const overlap = detectSessionOverlap("london", "newyork", nowMs);
    expect(overlap.ok && overlap.overlapping).toBe(false);
    const reply = renderSessionReply(nowMs);
    expect(reply).toContain("London / New York overlap: NO");
    expect(reply).toContain("London — OPEN");
    expect(reply).toContain("New York — CLOSED");
  });

  it("keeps DST overlap windows from the existing detector", () => {
    const winter = utc("2026-01-12T16:30:00.000Z");
    expect(renderSessionReply(winter)).toContain("London / New York overlap: YES");

    const summerLondonClosed = utc("2026-07-13T16:30:00.000Z");
    expect(renderSessionReply(summerLondonClosed)).toContain("London / New York overlap: NO");
    expect(renderSessionReply(summerLondonClosed)).toContain("London — CLOSED");
    expect(renderSessionReply(summerLondonClosed)).toContain("New York — OPEN");

    const springMismatch = utc("2026-03-09T12:00:00.000Z");
    expect(detectSessionOverlap("london", "newyork", springMismatch)).toMatchObject({
      ok: true,
      overlapping: true,
    });
    expect(renderSessionReply(springMismatch)).toContain("London / New York overlap: YES");
  });

  it("marks weekend sessions closed with no overlap", () => {
    const reply = renderSessionReply(utc("2026-01-17T14:00:00.000Z"));
    expect(reply).toContain("Sydney — CLOSED");
    expect(reply).toContain("Tokyo (Asian session) — CLOSED");
    expect(reply).toContain("London — CLOSED");
    expect(reply).toContain("New York — CLOSED");
    expect(reply).toContain("London / New York overlap: NO");
  });

  it("does not invent prices or trading signals", () => {
    const reply = renderSessionReply(utc("2026-01-12T14:00:00.000Z"));
    for (const pattern of SIGNAL_PATTERNS) {
      expect(reply, String(pattern)).not.toMatch(pattern);
    }
    expect(reply).toContain("does not give trading signals");
  });
});

describe("Telegram command copy", () => {
  it("lists /timeframe and /session on /help", () => {
    const reply = renderHelpReply();
    expect(reply).toContain("/timeframe — Supported chart timeframes (M1–D1)");
    expect(reply).toContain("/session — Current Forex session status and London/New York overlap");
  });

  it("mentions /timeframe and /session on /start", () => {
    const reply = renderStartReply();
    expect(reply).toContain("/timeframe");
    expect(reply).toContain("/session");
  });
});
