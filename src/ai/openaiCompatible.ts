import type { AiConfig } from "../config/env.js";
import type { AiChatOptions, AiChatResult, AiMessage, AiProvider } from "./types.js";

type ChatCompletionsResponse = {
  choices?: Array<{
    finish_reason?: string | null;
    message?: {
      content?: unknown;
    };
  }>;
  usage?: {
    prompt_tokens?: number;
    completion_tokens?: number;
  };
};

export const CHAT_COMPLETIONS_PATH = "chat/completions";

export function chatCompletionsUrl(baseUrl: string): string {
  return `${baseUrl.replace(/\/+$/, "")}/${CHAT_COMPLETIONS_PATH}`;
}

export class OpenAiCompatibleProvider implements AiProvider {
  readonly name = "openai-compatible";

  constructor(private readonly config: AiConfig) {}

  get model(): string {
    return this.config.model;
  }

  async chat(
    messages: readonly AiMessage[],
    signal?: AbortSignal,
    options?: AiChatOptions,
  ): Promise<AiChatResult> {
    const url = chatCompletionsUrl(this.config.baseUrl);
    const payload: {
      model: string;
      messages: readonly AiMessage[];
      max_tokens?: number;
      response_format?: AiChatOptions["responseFormat"];
      reasoning_effort?: "low";
    } = {
      model: this.config.model,
      messages,
    };
    if (options !== undefined) {
      payload.max_tokens = options.maxOutputTokens;
      if (options.responseFormat !== undefined) {
        payload.response_format = options.responseFormat;
      }
      if (options.reasoningEffort !== undefined) {
        payload.reasoning_effort = options.reasoningEffort;
      }
    }

    const init: RequestInit = {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${this.config.apiKey}`,
      },
      body: JSON.stringify(payload),
    };
    if (signal !== undefined) {
      init.signal = signal;
    }

    let response: Response;
    try {
      response = await fetch(url, init);
    } catch (error: unknown) {
      if (
        error instanceof Error &&
        (error.name === "AbortError" || error.name === "TimeoutError")
      ) {
        throw error;
      }
      throw new Error("AI request failed before a response was received.");
    }

    if (!response.ok) {
      throw new Error(`AI request failed with HTTP ${response.status}.`);
    }

    let body: ChatCompletionsResponse;
    try {
      body = (await response.json()) as ChatCompletionsResponse;
    } catch {
      throw new Error("AI response was not valid JSON.");
    }

    const choice = body.choices?.[0];
    if (choice?.finish_reason === "length") {
      throw new Error("AI response was truncated.");
    }
    if (choice?.finish_reason === "content_filter") {
      throw new Error("AI response was blocked by the provider.");
    }
    const content = choice?.message?.content;
    if (typeof content === "string") {
      const text = content.trim();
      if (text.length === 0) {
        throw new Error("AI response did not include assistant text.");
      }
      const result: AiChatResult = {
        text,
        inputTokens: body.usage?.prompt_tokens ?? 0,
        outputTokens: body.usage?.completion_tokens ?? 0,
      };
      const finishReason = choice?.finish_reason;
      if (typeof finishReason === "string" && finishReason.length > 0) {
        result.finishReason = finishReason;
      }
      return result;
    }
    if (content === undefined || content === null) {
      throw new Error("AI response did not include assistant text.");
    }
    throw new Error("AI response content was not text.");
  }
}

export function createOpenAiCompatibleProvider(config: AiConfig): AiProvider {
  return new OpenAiCompatibleProvider(config);
}
