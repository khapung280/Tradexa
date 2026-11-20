import { Bot } from "grammy";
import { logger } from "../config/logger.js";
import { DEFAULT_RISK_LIMITS, type RiskLimits } from "../risk/limits.js";
import { handleAnalyze } from "./analyze.js";
import { handleBacktest } from "./backtest.js";
import { handleDecision, handleDecisionHistory } from "./decision.js";
import { handlePrivateText, type ChatCommandDeps } from "./chat.js";
import { handleHelp } from "./help.js";
import { handleLearn } from "./learn.js";
import { handleRisk } from "./risk.js";
import { handleSession } from "./session.js";
import { handleStart } from "./start.js";
import { handleTimeframe } from "./timeframe.js";

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
    await handleAnalyze(ctx, {
      provider: deps.provider,
      budget: deps.budget,
    });
  });

  bot.command("backtest", async (ctx) => {
    logger.info({ command: "backtest", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleBacktest(ctx);
  });

  bot.command("decision", async (ctx) => {
    logger.info({ command: "decision", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleDecision(ctx, {
      store: deps.store,
      provider: deps.provider,
      budget: deps.budget,
      riskLimits,
    });
  });

  bot.command("decisions", async (ctx) => {
    logger.info({ command: "decisions", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleDecisionHistory(ctx, {
      store: deps.store,
      provider: deps.provider,
      budget: deps.budget,
      riskLimits,
    });
  });

  bot.command("timeframe", async (ctx) => {
    logger.info({ command: "timeframe", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleTimeframe(ctx);
  });

  bot.command("session", async (ctx) => {
    logger.info({ command: "session", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleSession(ctx);
  });

  bot.command("risk", async (ctx) => {
    logger.info({ command: "risk", chatId: ctx.chat?.id ?? null }, "Command received");
    await handleRisk(ctx, riskLimits, deps.store);
  });

  bot.on("message:text", async (ctx) => {
    await handlePrivateText(ctx, deps);
  });
}
