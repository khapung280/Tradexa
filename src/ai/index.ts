import type { AiConfig } from "../config/env.js";
import { createOpenAiCompatibleProvider } from "./openaiCompatible.js";
import type { AiProvider } from "./types.js";

export type {
  AiChatOptions,
  AiChatResult,
  AiMessage,
  AiProvider,
  AiRole,
} from "./types.js";
export {
  CHAT_COMPLETIONS_PATH,
  OpenAiCompatibleProvider,
  chatCompletionsUrl,
  createOpenAiCompatibleProvider,
} from "./openaiCompatible.js";

/** Builds the configured OpenAI-compatible provider (Gemini-capable via base URL). */
export function createAiProvider(config: AiConfig): AiProvider {
  return createOpenAiCompatibleProvider(config);
}
