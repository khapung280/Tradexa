import { Bot } from "grammy";
import { logger } from "../config/logger.js";
import { handlePrivateText, type ChatCommandDeps } from "./chat.js";
import { handleHelp } from "./help.js";
import { handleStart } from "./start.js";

export type { ChatCommandDeps };

export function registerCommands(bot: Bot, deps: ChatCommandDeps): void {
  bot.command("start", async (ctx) => {
    logger.info({ command: "start", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleStart(ctx);
  });

  bot.command("help", async (ctx) => {
    logger.info({ command: "help", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleHelp(ctx);
  });

  bot.on("message:text", async (ctx) => {
    await handlePrivateText(ctx, deps);
  });
}
