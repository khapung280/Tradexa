import { describe, expect, it } from "vitest";
import { buildChatSystemPrompt } from "../src/chat/process.ts";
import { buildEducationSystemPrompt } from "../src/education/prompt.ts";
import { buildMarketAiContext } from "../src/market/context.ts";
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

const WINTER_OVERLAP = utc("2026-01-12T14:00:00.000Z");
const SUMMER_LONDON_CLOSED = utc("2026-07-13T16:30:00.000Z");
const WEEKEND = utc("2026-01-17T14:00:00.000Z");

describe("buildMarketAiContext", () => {
  it("lists all seven supported timeframes", () => {
    const context = buildMarketAiContext(WINTER_OVERLAP);
    expect(TIMEFRAME_IDS).toEqual(["M1", "M5", "M15", "M30", "H1", "H4", "D1"]);
    for (const timeframe of TIMEFRAMES) {
      expect(context).toContain(
        `${timeframe.id} — ${timeframe.displayName} (${timeframe.minutes} minutes)`,
      );
    }
  });

  it("includes all four session states from detectSession", () => {
    const context = buildMarketAiContext(WINTER_OVERLAP);
    for (const session of TRADING_SESSIONS) {
      const detection = detectSession(session.id, WINTER_OVERLAP);
      expect(detection.ok).toBe(true);
      if (!detection.ok) {
        continue;
      }
      expect(context).toContain(
        `${session.displayName} [${session.id}] — ${detection.state.toUpperCase()}`,
      );
      expect(context).toContain(session.timezone);
    }
  });

  it("reports winter London/New York overlap as YES", () => {
    const overlap = detectSessionOverlap("london", "newyork", WINTER_OVERLAP);
    expect(overlap.ok && overlap.overlapping).toBe(true);
    const context = buildMarketAiContext(WINTER_OVERLAP);
    expect(context).toContain("London / New York overlap: YES");
    expect(context).toContain("London [london] — OPEN");
    expect(context).toContain("New York [newyork] — OPEN");
  });

  it("reports summer London closed while New York is open, overlap NO", () => {
    const overlap = detectSessionOverlap("london", "newyork", SUMMER_LONDON_CLOSED);
    expect(overlap.ok && overlap.overlapping).toBe(false);
    expect(detectSession("london", SUMMER_LONDON_CLOSED)).toMatchObject({
      ok: true,
      state: "closed",
    });
    expect(detectSession("newyork", SUMMER_LONDON_CLOSED)).toMatchObject({
      ok: true,
      state: "open",
    });
    const context = buildMarketAiContext(SUMMER_LONDON_CLOSED);
    expect(context).toContain("London [london] — CLOSED");
    expect(context).toContain("New York [newyork] — OPEN");
    expect(context).toContain("London / New York overlap: NO");
  });

  it("marks weekend sessions closed with overlap NO", () => {
    const context = buildMarketAiContext(WEEKEND);
    expect(context).toContain("Sydney [sydney] — CLOSED");
    expect(context).toContain("Tokyo (Asian session) [tokyo] — CLOSED");
    expect(context).toContain("London [london] — CLOSED");
    expect(context).toContain("New York [newyork] — CLOSED");
    expect(context).toContain("London / New York overlap: NO");
  });

  it("is deterministic for an injected clock", () => {
    expect(buildMarketAiContext(WINTER_OVERLAP)).toBe(buildMarketAiContext(WINTER_OVERLAP));
    expect(buildMarketAiContext(WINTER_OVERLAP)).not.toBe(
      buildMarketAiContext(SUMMER_LONDON_CLOSED),
    );
  });

  it("includes safety rules against invented market data and signals", () => {
    const context = buildMarketAiContext(WINTER_OVERLAP);
    expect(context).toMatch(/Never invent live prices/i);
    expect(context).toMatch(/OHLC/i);
    expect(context).toMatch(/spreads/i);
    expect(context).toMatch(/market movement/i);
    expect(context).toMatch(/Never provide buy\/sell signals/i);
    expect(context).toMatch(/Live market data is not connected/i);
    expect(context).toContain("/timeframe");
    expect(context).toContain("/session");
    for (const pattern of SIGNAL_PATTERNS) {
      expect(context, String(pattern)).not.toMatch(pattern);
    }
  });
});

describe("buildChatSystemPrompt", () => {
  it("prefixes the education prompt with per-request market context", () => {
    const prompt = buildChatSystemPrompt(WINTER_OVERLAP);
    const education = buildEducationSystemPrompt();
    const market = buildMarketAiContext(WINTER_OVERLAP);
    expect(prompt.startsWith(education)).toBe(true);
    expect(prompt).toContain(market);
    expect(prompt).not.toBe(education);
  });
});
