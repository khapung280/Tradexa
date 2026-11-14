import type { AiChatOptions, AiMessage, AiProvider } from "../ai/types.js";
import type { TokenBudget } from "../budget/ledger.js";
import { logger } from "../config/logger.js";
import type { ChatStore } from "../db/store.js";
import { buildEducationSystemPrompt } from "../education/prompt.js";
import { splitTelegramText } from "./split.js";

export const AI_TIMEOUT_MS = 20_000;
export const AI_MAX_RETRIES = 2;
export const AI_RETRY_BACKOFF_MS = 250;

export const SYSTEM_PROMPT = buildEducationSystemPrompt();

export const AI_UNCONFIGURED_REPLY = [
  "AI replies are not configured yet.",
  "Set AI_API_KEY, AI_BASE_URL, and AI_MODEL in the .env file, then restart Tradexa.",
].join("\n");

export const AI_BUSY_REPLY = "Please wait until I finish your previous message.";

export const AI_ERROR_REPLY =
  "Sorry, I could not complete that request. Please try again in a moment.";

export const AI_CHAT_LIMIT_REPLY =
  "You have reached today's AI usage limit for this chat. Please try again after 00:00 UTC.";

export const AI_GLOBAL_LIMIT_REPLY =
  "Tradexa has reached today's AI usage limit. Please try again after 00:00 UTC.";

export type ProcessDeps = {
  store: ChatStore;
  provider: AiProvider | null;
  budget: TokenBudget;
  inflight: Set<number>;
  timeoutMs: number;
  maxRetries: number;
  delay: (ms: number) => Promise<void>;
};

export type ProcessResult =
  | { status: "ignored" }
  | { status: "unconfigured"; reply: string }
  | { status: "busy"; reply: string }
  | { status: "limited"; reply: string }
  | { status: "ok"; replies: string[]; inputTokens: number; outputTokens: number }
  | { status: "error"; reply: string };

export function shouldHandlePrivateText(
  chatType: string | undefined,
  text: string,
): boolean {
  if (chatType !== "private") {
    return false;
  }
  const trimmed = text.trim();
  return trimmed.length > 0 && !trimmed.startsWith("/");
}

export function defaultProcessDeps(
  store: ChatStore,
  provider: AiProvider | null,
  inflight: Set<number>,
  budget: TokenBudget,
): ProcessDeps {
  return {
    store,
    provider,
    budget,
    inflight,
    timeoutMs: AI_TIMEOUT_MS,
    maxRetries: AI_MAX_RETRIES,
    delay: delayMs,
  };
}

export async function processPrivateText(
  chatId: number,
  text: string,
  deps: ProcessDeps,
): Promise<ProcessResult> {
  const trimmed = text.trim();
  if (trimmed.length === 0 || trimmed.startsWith("/")) {
    return { status: "ignored" };
  }

  if (deps.provider === null) {
    return { status: "unconfigured", reply: AI_UNCONFIGURED_REPLY };
  }

  if (deps.inflight.has(chatId)) {
    return { status: "busy", reply: AI_BUSY_REPLY };
  }

  deps.inflight.add(chatId);
  const provider = deps.provider;
  let reservationId: number | undefined;
  try {
    const pendingMessages: AiMessage[] = [
      { role: "system", content: SYSTEM_PROMPT },
      ...deps.store.getHistory(chatId),
      { role: "user", content: trimmed },
    ];
    const reserved = await deps.budget.reserve(chatId, pendingMessages);
    if (!reserved.ok) {
      return {
        status: "limited",
        reply: reserved.kind === "chat" ? AI_CHAT_LIMIT_REPLY : AI_GLOBAL_LIMIT_REPLY,
      };
    }
    reservationId = reserved.reservationId;

    deps.store.saveMessage(chatId, "user", trimmed);
    const messages: AiMessage[] = [
      { role: "system", content: SYSTEM_PROMPT },
      ...deps.store.getHistory(chatId),
    ];

    const result = await chatWithRetry(provider, messages, deps, {
      maxOutputTokens: reserved.maxOutputTokens,
    });
    deps.store.saveMessage(chatId, "assistant", result.text);
    await deps.budget.settle(reserved.reservationId, {
      chatId,
      provider: provider.name,
      model: provider.model,
      inputTokens: result.inputTokens,
      outputTokens: result.outputTokens,
    });
    reservationId = undefined;

    logger.info(
      {
        chatId,
        provider: provider.name,
        model: provider.model,
        inputTokens: result.inputTokens,
        outputTokens: result.outputTokens,
        reservedTokens: reserved.reservedTokens,
        maxOutputTokens: reserved.maxOutputTokens,
      },
      "AI reply ready",
    );

    return {
      status: "ok",
      replies: splitTelegramText(result.text),
      inputTokens: result.inputTokens,
      outputTokens: result.outputTokens,
    };
  } catch (error: unknown) {
    const classified = classifyAiError(error);
    logger.error(
      {
        err: error,
        chatId,
        provider: provider.name,
        model: provider.model,
        errorType: classified.type,
        status: classified.status ?? null,
      },
      "AI request failed",
    );
    return { status: "error", reply: AI_ERROR_REPLY };
  } finally {
    if (reservationId !== undefined) {
      await deps.budget.release(reservationId);
    }
    deps.inflight.delete(chatId);
  }
}

function delayMs(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

type ClassifiedAiError = {
  type: string;
  retryable: boolean;
  status?: number;
};

export function classifyAiError(error: unknown): ClassifiedAiError {
  if (error instanceof Error && (error.name === "AbortError" || error.name === "TimeoutError")) {
    return { type: "timeout", retryable: false };
  }

  const message = error instanceof Error ? error.message : String(error);
  const httpMatch = /HTTP (\d{3})/.exec(message);
  if (httpMatch !== null) {
    const status = Number(httpMatch[1]);
    const retryable = status === 429 || (status >= 500 && status <= 599);
    return { type: `http_${status}`, retryable, status };
  }

  if (message.includes("before a response was received")) {
    return { type: "network", retryable: true };
  }

  return { type: "error", retryable: false };
}

async function chatWithRetry(
  provider: AiProvider,
  messages: readonly AiMessage[],
  deps: ProcessDeps,
  options: AiChatOptions,
) {
  let lastError: unknown;
  for (let attempt = 0; attempt <= deps.maxRetries; attempt += 1) {
    try {
      return await chatOnce(provider, messages, deps.timeoutMs, options);
    } catch (error: unknown) {
      lastError = error;
      const classified = classifyAiError(error);
      if (!classified.retryable || attempt === deps.maxRetries) {
        throw error;
      }
      await deps.delay(AI_RETRY_BACKOFF_MS * (attempt + 1));
    }
  }
  throw lastError;
}

async function chatOnce(
  provider: AiProvider,
  messages: readonly AiMessage[],
  timeoutMs: number,
  options: AiChatOptions,
) {
  const signal = AbortSignal.timeout(timeoutMs);
  return await Promise.race([
    provider.chat(messages, signal, options),
    timeoutRejection(signal),
  ]);
}

function timeoutRejection(signal: AbortSignal): Promise<never> {
  return new Promise((_, reject) => {
    const fail = (): void => {
      const error = new Error("AI request timed out.");
      error.name = "TimeoutError";
      reject(error);
    };
    if (signal.aborted) {
      fail();
      return;
    }
    signal.addEventListener("abort", fail, { once: true });
  });
}
