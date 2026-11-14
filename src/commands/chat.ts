import type { Context } from "grammy";
import type { AiProvider } from "../ai/types.js";
import type { TokenBudget } from "../budget/ledger.js";
import { defaultProcessDeps, processPrivateText, shouldHandlePrivateText } from "../chat/process.js";
import { logger } from "../config/logger.js";
import type { ChatStore } from "../db/store.js";

export type ChatCommandDeps = {
  store: ChatStore;
  provider: AiProvider | null;
  budget: TokenBudget;
  inflight: Set<number>;
};

const TYPING_REFRESH_MS = 4_000;

export async function handlePrivateText(
  ctx: Context,
  deps: ChatCommandDeps,
): Promise<void> {
  const chat = ctx.chat;
  const text = ctx.message?.text;
  if (chat === undefined || text === undefined) {
    return;
  }
  if (!shouldHandlePrivateText(chat.type, text)) {
    return;
  }

  const typing = startTyping(ctx);
  try {
    const result = await processPrivateText(
      chat.id,
      text,
      defaultProcessDeps(deps.store, deps.provider, deps.inflight, deps.budget),
    );

    if (result.status === "ignored") {
      return;
    }
    if (result.status === "ok") {
      for (const part of result.replies) {
        await ctx.reply(part);
      }
      return;
    }
    await ctx.reply(result.reply);
  } catch (error: unknown) {
    logger.error(
      { err: error, chatId: chat.id },
      "Failed to send an AI chat reply",
    );
  } finally {
    typing.stop();
  }
}

function startTyping(ctx: Context): { stop(): void } {
  let stopped = false;
  const send = (): void => {
    if (stopped) {
      return;
    }
    void ctx.replyWithChatAction("typing").catch(() => {
      // Typing is best-effort and must not fail the chat reply.
    });
  };

  send();
  const timer = setInterval(send, TYPING_REFRESH_MS);
  return {
    stop() {
      stopped = true;
      clearInterval(timer);
    },
  };
}
