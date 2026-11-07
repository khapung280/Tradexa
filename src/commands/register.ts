import { Bot } from "grammy";
import { logger } from "../config/logger.js";
import { handleHelp } from "./help.js";
import { handleStart } from "./start.js";

export function registerCommands(bot: Bot): void {
  bot.command("start", async (ctx) => {
    logger.info({ command: "start", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleStart(ctx);
  });

  bot.command("help", async (ctx) => {
    logger.info({ command: "help", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleHelp(ctx);
  });
}
