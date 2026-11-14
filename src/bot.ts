import { Bot } from "grammy";
import { createAiProvider, type AiProvider } from "./ai/index.js";
import { TokenBudget } from "./budget/ledger.js";
import { registerCommands } from "./commands/register.js";
import { loadEnv } from "./config/env.js";
import { logger } from "./config/logger.js";
import { openChatStore, type ChatStore } from "./db/store.js";

const SHUTDOWN_TIMEOUT_MS = 10_000;

async function main(): Promise<void> {
  const { botToken, ai, budget: budgetLimits } = loadEnv();
  const store = openChatStore();
  const provider = ai === null ? null : createAiProvider(ai);
  const budget = new TokenBudget(store, budgetLimits);
  logger.info({ databasePath: store.path }, "Database ready");

  try {
    await runBot(botToken, store, provider, budget);
  } finally {
    store.close();
    logger.info("Database closed");
  }
}

async function runBot(
  botToken: string,
  store: ChatStore,
  provider: AiProvider | null,
  budget: TokenBudget,
): Promise<void> {
  const bot = new Bot(botToken, {
    client: { sensitiveLogs: false },
  });

  registerCommands(bot, {
    store,
    provider,
    budget,
    inflight: new Set<number>(),
  });

  bot.catch((botError) => {
    logger.error(
      {
        err: botError.error,
        updateId: botError.ctx.update.update_id,
        chatId: botError.ctx.chat?.id ?? null,
      },
      "Failed to handle a Telegram update",
    );
  });

  let shuttingDown = false;

  const shutdown = (signal: string): void => {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    logger.info({ signal }, "Shutdown requested");

    const forceExit = setTimeout(() => {
      logger.error("Shutdown timed out");
      process.exit(1);
    }, SHUTDOWN_TIMEOUT_MS);
    forceExit.unref();

    void bot
      .stop()
      .then(() => {
        clearTimeout(forceExit);
        logger.info("Bot stopped");
      })
      .catch((error: unknown) => {
        clearTimeout(forceExit);
        logger.error({ err: error }, "Failed to stop the bot");
        process.exit(1);
      });
  };

  process.once("SIGINT", () => {
    shutdown("SIGINT");
  });
  process.once("SIGTERM", () => {
    shutdown("SIGTERM");
  });

  logger.info(
    {
      aiConfigured: provider !== null,
      provider: provider?.name ?? null,
      model: provider?.model ?? null,
      dailyGlobalTokens: budget.limits.dailyGlobalTokens,
      dailyChatTokens: budget.limits.dailyChatTokens,
      maxOutputTokens: budget.limits.maxOutputTokens,
    },
    "Environment loaded",
  );
  logger.info("Connecting to Telegram");

  await bot.start({
    onStart: (botInfo) => {
      logger.info(
        { id: botInfo.id, username: botInfo.username ?? null },
        "Connected to Telegram",
      );
    },
  });
}

main().catch((error: unknown) => {
  logger.error({ err: error }, "Bot failed to start");
  process.exit(1);
});
