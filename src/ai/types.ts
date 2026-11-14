export type AiRole = "system" | "user" | "assistant";

export type AiMessage = {
  role: AiRole;
  content: string;
};

export type AiChatResult = {
  text: string;
  inputTokens: number;
  outputTokens: number;
};

export type AiChatOptions = {
  maxOutputTokens: number;
};

/**
 * Provider-independent chat adapter. Implementations may call different hosts
 * (OpenAI, Gemini OpenAI-compatible, etc.) behind this shape.
 */
export interface AiProvider {
  readonly name: string;
  readonly model: string;
  chat(
    messages: readonly AiMessage[],
    signal?: AbortSignal,
    options?: AiChatOptions,
  ): Promise<AiChatResult>;
}
