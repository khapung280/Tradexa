import type { CommandContext, Context } from "grammy";

export async function handleHelp(ctx: CommandContext<Context>): Promise<void> {
  await ctx.reply(
    [
      "Tradexa commands:",
      "",
      "/start — Welcome message",
      "/help — Show this list",
      "",
      "Send a command as a message in this chat.",
    ].join("\n"),
  );
}
