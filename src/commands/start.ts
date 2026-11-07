import type { CommandContext, Context } from "grammy";

export async function handleStart(ctx: CommandContext<Context>): Promise<void> {
  await ctx.reply(
    [
      "Welcome to Tradexa.",
      "",
      "Tradexa is an AI-powered Forex Trading Assistant.",
      "",
      "Send /help to see the commands you can use.",
    ].join("\n"),
  );
}
