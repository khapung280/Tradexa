import { config as loadDotenv } from "dotenv";

const BOT_TOKEN_PATTERN = /^\d{6,}:[A-Za-z0-9_-]{30,}$/;

export function readBotToken(env: NodeJS.ProcessEnv): string {
  const raw = env.BOT_TOKEN;
  const token = typeof raw === "string" ? raw.trim() : "";
  if (!BOT_TOKEN_PATTERN.test(token)) {
    throw new Error(
      "BOT_TOKEN is missing or invalid. Copy .env.example to .env and set BOT_TOKEN to the token from BotFather.",
    );
  }
  return token;
}

export function loadEnv(env: NodeJS.ProcessEnv = process.env): { botToken: string } {
  const result = loadDotenv({ quiet: true });
  if (result.error !== undefined && result.error.code !== "ENOENT") {
    throw new Error(`Could not load the .env file (${result.error.code}).`);
  }
  return { botToken: readBotToken(env) };
}
