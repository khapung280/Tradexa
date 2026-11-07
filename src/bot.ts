import { Bot } from "grammy";
import { registerCommands } from "./commands/register.js";
import { loadEnv } from "./config/env.js";
import { logger } from "./config/logger.js";

const SHUTDOWN_TIMEOUT_MS = 10_000;

async function main(): Promise<void> {
  const { botToken } = loadEnv();
  const bot = new Bot(botToken, {
    client: { sensitiveLogs: false },
  });

  registerCommands(bot);

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

  logger.info("Environment loaded");
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
