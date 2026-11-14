import type { CommandContext, Context } from "grammy";

export async function handleStart(ctx: CommandContext<Context>): Promise<void> {
  await ctx.reply(
    [
      "Welcome to Tradexa.",
      "",
      "Tradexa is an AI-powered Forex Trading Assistant.",
      "",
      "Send /learn for Forex basics, /analyze for the analysis template, /risk for position size, /risk loss to report a loss, /risk status for today's reported loss, or /help for all commands.",
      "",
      "Losses are user-reported. Tradexa does not receive P/L from a broker or live trading account.",
    ].join("\n"),
  );
}
