import type { CommandContext, Context } from "grammy";

export async function handleStart(ctx: CommandContext<Context>): Promise<void> {
  await ctx.reply(
    [
      "Welcome to Tradexa.",
      "",
      "Tradexa is an AI-powered Forex Trading Assistant.",
      "",
      "Send /learn for Forex basics, /analyze for the analysis template, or /help for all commands.",
    ].join("\n"),
  );
}
