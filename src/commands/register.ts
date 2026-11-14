import { Bot } from "grammy";
import { logger } from "../config/logger.js";
import { DEFAULT_RISK_LIMITS, type RiskLimits } from "../risk/limits.js";
import { handleAnalyze } from "./analyze.js";
import { handlePrivateText, type ChatCommandDeps } from "./chat.js";
import { handleHelp } from "./help.js";
import { handleLearn } from "./learn.js";
import { handleRisk } from "./risk.js";
import { handleStart } from "./start.js";

export type { ChatCommandDeps };

export function registerCommands(
  bot: Bot,
  deps: ChatCommandDeps,
  riskLimits: RiskLimits = DEFAULT_RISK_LIMITS,
): void {
  bot.command("start", async (ctx) => {
    logger.info({ command: "start", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleStart(ctx);
  });

  bot.command("help", async (ctx) => {
    logger.info({ command: "help", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleHelp(ctx);
  });

  bot.command("learn", async (ctx) => {
    logger.info({ command: "learn", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleLearn(ctx);
  });

  bot.command("analyze", async (ctx) => {
    logger.info({ command: "analyze", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleAnalyze(ctx);
  });

  bot.command("risk", async (ctx) => {
    logger.info({ command: "risk", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleRisk(ctx, riskLimits, deps.store);
  });

  bot.on("message:text", async (ctx) => {
    await handlePrivateText(ctx, deps);
  });
}
