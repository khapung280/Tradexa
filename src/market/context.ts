import { detectSessionOverlap } from "./sessionOverlap.js";
import { detectSession } from "./sessionState.js";
import { TRADING_SESSIONS } from "./sessions.js";
import { TIMEFRAMES } from "./timeframes.js";

export function buildMarketAiContext(nowMs: number): string {
  const timeframes = TIMEFRAMES.map(
    (timeframe) => `${timeframe.id} — ${timeframe.displayName} (${timeframe.minutes} minutes)`,
  );

  const sessions = TRADING_SESSIONS.map((session) => {
    const detection = detectSession(session.id, nowMs);
    const state = detection.ok ? detection.state.toUpperCase() : "UNKNOWN";
    const local =
      detection.ok && detection.localTime.length > 0
        ? `${detection.localWeekday} ${detection.localTime}`
        : "n/a";
    return [
      `${session.displayName} [${session.id}] — ${state}`,
      `Local: ${local} (${session.timezone})`,
      `Hours: ${session.localOpen}–${session.localClose} Monday–Friday`,
    ].join("\n");
  });

  const overlap = detectSessionOverlap("london", "newyork", nowMs);
  const overlapLine = overlap.ok
    ? `London / New York overlap: ${overlap.overlapping ? "YES" : "NO"}`
    : "London / New York overlap: unavailable";

  return [
    "Trusted market-hours context at the injected clock. Prefer this over guessing session status:",
    "",
    "Supported timeframes:",
    ...timeframes,
    "",
    "Current trading sessions:",
    sessions.join("\n\n"),
    "",
    overlapLine,
    "",
    "Market-hours rules:",
    "- These are catalog chart periods and local session hours, not live broker or exchange status.",
    "- Never invent live prices, OHLC candles, spreads, or market movement from this context.",
    "- Never provide buy/sell signals, trade entries, or guaranteed outcomes.",
    "- OPEN/CLOSED and overlap are clock-derived from local hours, not a trading setup.",
    "- London / New York overlap means both sessions are open at this timestamp, nothing more.",
    "- Live market data is not connected.",
    "- If the user wants the timeframe list, tell them to use /timeframe.",
    "- If the user wants session status as a command, tell them to use /session.",
  ].join("\n");
}
