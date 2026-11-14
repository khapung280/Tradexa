import { config as loadDotenv } from "dotenv";
import {
  DEFAULT_MAX_DAILY_LOSS,
  DEFAULT_MAX_LOTS,
  DEFAULT_MAX_RISK_PERCENT,
  type RiskLimits,
} from "../risk/limits.js";

const BOT_TOKEN_PATTERN = /^\d{6,}:[A-Za-z0-9_-]{30,}$/;
const PLACEHOLDER_MODELS = new Set([
  "your_gemini_model",
  "your-gemini-model",
  "your_model",
]);

export const GEMINI_OPENAI_BASE_URL =
  "https://generativelanguage.googleapis.com/v1beta/openai";
export const DEFAULT_GEMINI_MODEL = "gemini-2.0-flash";
export const DEFAULT_DAILY_GLOBAL_TOKENS = 100_000;
export const DEFAULT_DAILY_CHAT_TOKENS = 20_000;
export const DEFAULT_MAX_OUTPUT_TOKENS = 2_048;

export type AiConfig = {
  apiKey: string;
  baseUrl: string;
  model: string;
};

export type BudgetConfig = {
  dailyGlobalTokens: number;
  dailyChatTokens: number;
  maxOutputTokens: number;
};

export type AppEnv = {
  botToken: string;
  ai: AiConfig | null;
  budget: BudgetConfig;
  risk: RiskLimits;
};

function readPositiveInt(
  env: NodeJS.ProcessEnv,
  key: string,
  fallback: number,
): number {
  const raw = readRequiredTrimmed(env, key);
  if (raw === undefined) {
    return fallback;
  }
  if (!/^[0-9]+$/.test(raw)) {
    throw new Error(`${key} must be a positive integer.`);
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${key} must be a positive integer.`);
  }
  return value;
}

function readRequiredTrimmed(
  env: NodeJS.ProcessEnv,
  key: string,
): string | undefined {
  const raw = env[key];
  if (typeof raw !== "string") {
    return undefined;
  }
  const trimmed = raw.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

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

/**
 * AI settings are optional at startup. Missing all three yields null.
 * Providing any one without the others is a configuration error.
 */
export function readAiConfig(env: NodeJS.ProcessEnv): AiConfig | null {
  const apiKey = readRequiredTrimmed(env, "AI_API_KEY");
  const baseUrl = readRequiredTrimmed(env, "AI_BASE_URL");
  const model = readRequiredTrimmed(env, "AI_MODEL");

  const present = [apiKey, baseUrl, model].filter((value) => value !== undefined);
  if (present.length === 0) {
    return null;
  }
  if (apiKey === undefined || baseUrl === undefined || model === undefined) {
    throw new Error(
      "AI configuration is incomplete. Set AI_API_KEY, AI_BASE_URL, and AI_MODEL together, or omit all three.",
    );
  }

  let normalizedBaseUrl: string;
  try {
    normalizedBaseUrl = new URL(baseUrl).toString().replace(/\/+$/, "");
  } catch {
    throw new Error(
      `AI_BASE_URL is invalid. Use a full URL such as ${GEMINI_OPENAI_BASE_URL}`,
    );
  }

  if (PLACEHOLDER_MODELS.has(model.toLowerCase())) {
    throw new Error(
      `AI_MODEL is a placeholder. Set it to a Gemini model such as ${DEFAULT_GEMINI_MODEL}.`,
    );
  }

  return {
    apiKey,
    baseUrl: normalizedBaseUrl,
    model,
  };
}

export function readBudgetConfig(env: NodeJS.ProcessEnv): BudgetConfig {
  return {
    dailyGlobalTokens: readPositiveInt(
      env,
      "AI_DAILY_TOKEN_LIMIT",
      DEFAULT_DAILY_GLOBAL_TOKENS,
    ),
    dailyChatTokens: readPositiveInt(
      env,
      "AI_CHAT_DAILY_TOKEN_LIMIT",
      DEFAULT_DAILY_CHAT_TOKENS,
    ),
    maxOutputTokens: readPositiveInt(
      env,
      "AI_MAX_OUTPUT_TOKENS",
      DEFAULT_MAX_OUTPUT_TOKENS,
    ),
  };
}

function readPositiveNumber(
  env: NodeJS.ProcessEnv,
  key: string,
  fallback: number,
): number {
  const raw = readRequiredTrimmed(env, key);
  if (raw === undefined) {
    return fallback;
  }
  if (!/^[0-9]+(?:\.[0-9]+)?$/.test(raw)) {
    throw new Error(`${key} must be a positive number.`);
  }
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${key} must be a positive number.`);
  }
  return value;
}

export function readRiskLimits(env: NodeJS.ProcessEnv): RiskLimits {
  return {
    maxRiskPercent: readPositiveNumber(
      env,
      "RISK_MAX_PERCENT",
      DEFAULT_MAX_RISK_PERCENT,
    ),
    maxLots: readPositiveNumber(env, "RISK_MAX_LOTS", DEFAULT_MAX_LOTS),
    maxDailyLoss: readPositiveNumber(
      env,
      "RISK_MAX_DAILY_LOSS",
      DEFAULT_MAX_DAILY_LOSS,
    ),
  };
}

export function resolveEnv(env: NodeJS.ProcessEnv): AppEnv {
  return {
    botToken: readBotToken(env),
    ai: readAiConfig(env),
    budget: readBudgetConfig(env),
    risk: readRiskLimits(env),
  };
}

export function loadEnv(env: NodeJS.ProcessEnv = process.env): AppEnv {
  const result = loadDotenv({ quiet: true });
  if (result.error !== undefined && result.error.code !== "ENOENT") {
    throw new Error(`Could not load the .env file (${result.error.code}).`);
  }
  return resolveEnv(env);
}
