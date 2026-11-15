import type { CommandContext, Context } from "grammy";
import { TIMEFRAMES, findTimeframe, type Timeframe } from "../market/timeframes.js";

function formatTimeframe(timeframe: Timeframe): string {
  return `${timeframe.id} — ${timeframe.displayName}`;
}

export function formatTimeframeList(): string {
  return [
    "Supported Forex timeframes:",
    "",
    ...TIMEFRAMES.map((timeframe) => formatTimeframe(timeframe)),
    "",
    "These are chart periods only. Tradexa does not download market data here.",
  ].join("\n");
}

export function renderTimeframeReply(argument: string): string {
  const id = argument.trim();
  if (id.length === 0) {
    return formatTimeframeList();
  }

  const timeframe = findTimeframe(id);
  if (timeframe === undefined) {
    return [`Unknown timeframe "${id}".`, "", formatTimeframeList()].join("\n");
  }

  return [
    formatTimeframe(timeframe),
    `Duration: ${timeframe.minutes} minutes`,
    "",
    "This is a chart period only. Tradexa does not download market data here.",
  ].join("\n");
}

export async function handleTimeframe(ctx: CommandContext<Context>): Promise<void> {
  const argument = typeof ctx.match === "string" ? ctx.match : "";
  await ctx.reply(renderTimeframeReply(argument));
}
