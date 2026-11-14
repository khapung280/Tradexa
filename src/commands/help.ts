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
      "/risk EURUSD 10000 USD 1 50 — Position size (example)",
      "/risk loss 50 USD — Record a user-reported loss",
      "/risk status — Today's reported loss and kill switch",
      "",
      "Losses are user-reported. Tradexa does not receive P/L from a broker or live trading account.",
      "",
      "Send a command as a message in this chat.",
      "In a private chat you can also send a normal message to talk to Tradexa.",
    ].join("\n"),
  );
}
