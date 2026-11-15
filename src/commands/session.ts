import type { CommandContext, Context } from "grammy";
import { detectSessionOverlap } from "../market/sessionOverlap.js";
import { detectSession } from "../market/sessionState.js";
import { TRADING_SESSIONS } from "../market/sessions.js";

function formatSessionState(nowMs: number): string {
  const blocks: string[] = [];
  for (const session of TRADING_SESSIONS) {
    const detection = detectSession(session.id, nowMs);
    const state = detection.ok ? detection.state.toUpperCase() : "UNKNOWN";
    const local =
      detection.ok && detection.localTime.length > 0
        ? `${detection.localWeekday} ${detection.localTime}`
        : "n/a";
    blocks.push(
      [
        `${session.displayName} — ${state}`,
        `Local: ${local} (${session.timezone})`,
        `Hours: ${session.localOpen}–${session.localClose} Mon–Fri`,
      ].join("\n"),
    );
  }
  return blocks.join("\n\n");
}

function formatLondonNewYorkOverlap(nowMs: number): string {
  const overlap = detectSessionOverlap("london", "newyork", nowMs);
  if (!overlap.ok) {
    return "London / New York overlap: unavailable";
  }
  return `London / New York overlap: ${overlap.overlapping ? "YES" : "NO"}`;
}

export function renderSessionReply(nowMs: number): string {
  return [
    "Forex trading sessions:",
    "",
    formatSessionState(nowMs),
    "",
    formatLondonNewYorkOverlap(nowMs),
    "",
    "Hours are local to each session. Daylight saving follows the session timezone.",
    "This is session status only. Tradexa does not give trading signals.",
  ].join("\n");
}

export async function handleSession(
  ctx: CommandContext<Context>,
  now: () => number = Date.now,
): Promise<void> {
  await ctx.reply(renderSessionReply(now()));
}
