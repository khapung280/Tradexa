import type { CommandContext, Context } from "grammy";

export async function handleHelp(ctx: CommandContext<Context>): Promise<void> {
  await ctx.reply(
    [
      "Tradexa commands:",
      "",
      "/start — Welcome message",
      "/help — Show this list",
      "/learn — List Forex lessons",
      "/learn pips — Open one lesson (example)",
      "/analyze — How to read a chart (no live market data)",
      "",
      "Send a command as a message in this chat.",
      "In a private chat you can also send a normal message to talk to Tradexa.",
    ].join("\n"),
  );
}
