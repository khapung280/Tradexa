import { Writable } from "node:stream";
import { describe, expect, it } from "vitest";
import {
  DEFAULT_DAILY_CHAT_TOKENS,
  DEFAULT_DAILY_GLOBAL_TOKENS,
  DEFAULT_MAX_OUTPUT_TOKENS,
  readAiConfig,
  readBotToken,
  readBudgetConfig,
  resolveEnv,
} from "../src/config/env.ts";
import { createLogger, redactSecrets } from "../src/config/logger.ts";

const sampleToken = `123456789:${"A".repeat(35)}`;
const sampleApiKey = `AIzaSy${"B".repeat(33)}`;
const sampleAqKey = `AQ.${"C".repeat(40)}`;

describe("readBotToken", () => {
  it("returns a well-formed token", () => {
    expect(readBotToken({ BOT_TOKEN: `  ${sampleToken}  ` })).toBe(sampleToken);
  });

  it("rejects a missing or invalid token without echoing the value", () => {
    const invalid = "super-secret-value";
    expect(() => readBotToken({})).toThrow(/BOT_TOKEN is missing or invalid/);

    try {
      readBotToken({ BOT_TOKEN: invalid });
      expect.fail("invalid token should throw");
    } catch (error) {
      expect(error).toBeInstanceOf(Error);
      expect((error as Error).message.includes(invalid)).toBe(false);
    }
  });
});

describe("readAiConfig", () => {
  it("returns null when AI settings are omitted", () => {
    expect(readAiConfig({ BOT_TOKEN: sampleToken })).toBeNull();
  });

  it("returns trimmed AI settings when all three are set", () => {
    expect(
      readAiConfig({
        AI_API_KEY: `  ${sampleApiKey}  `,
        AI_BASE_URL: " https://generativelanguage.googleapis.com/v1beta/openai/ ",
        AI_MODEL: " gemini-2.0-flash ",
      }),
    ).toEqual({
      apiKey: sampleApiKey,
      baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai",
      model: "gemini-2.0-flash",
    });
  });

  it("rejects a partial AI configuration without echoing the key", () => {
    try {
      readAiConfig({
        AI_API_KEY: sampleApiKey,
        AI_BASE_URL: "https://generativelanguage.googleapis.com/v1beta/openai",
      });
      expect.fail("partial AI config should throw");
    } catch (error) {
      expect(error).toBeInstanceOf(Error);
      expect((error as Error).message).toMatch(/AI configuration is incomplete/);
      expect((error as Error).message.includes(sampleApiKey)).toBe(false);
    }
  });

  it("rejects a placeholder AI_MODEL without echoing the key", () => {
    try {
      readAiConfig({
        AI_API_KEY: sampleApiKey,
        AI_BASE_URL: "https://generativelanguage.googleapis.com/v1beta/openai/",
        AI_MODEL: "your_gemini_model",
      });
      expect.fail("placeholder AI_MODEL should throw");
    } catch (error) {
      expect(error).toBeInstanceOf(Error);
      expect((error as Error).message).toMatch(/AI_MODEL is a placeholder/);
      expect((error as Error).message).toMatch(/gemini-2\.0-flash/);
      expect((error as Error).message.includes(sampleApiKey)).toBe(false);
    }
  });

  it("rejects an invalid AI_BASE_URL without echoing the key", () => {
    try {
      readAiConfig({
        AI_API_KEY: sampleApiKey,
        AI_BASE_URL: "not-a-url",
        AI_MODEL: "gemini-2.0-flash",
      });
      expect.fail("invalid AI_BASE_URL should throw");
    } catch (error) {
      expect(error).toBeInstanceOf(Error);
      expect((error as Error).message).toMatch(/AI_BASE_URL is invalid/);
      expect((error as Error).message.includes(sampleApiKey)).toBe(false);
    }
  });
});

describe("resolveEnv", () => {
  it("keeps AI optional while requiring BOT_TOKEN", () => {
    expect(resolveEnv({ BOT_TOKEN: sampleToken })).toEqual({
      botToken: sampleToken,
      ai: null,
      budget: {
        dailyGlobalTokens: DEFAULT_DAILY_GLOBAL_TOKENS,
        dailyChatTokens: DEFAULT_DAILY_CHAT_TOKENS,
        maxOutputTokens: DEFAULT_MAX_OUTPUT_TOKENS,
      },
    });
  });
});

describe("readBudgetConfig", () => {
  it("uses defaults when budget variables are omitted", () => {
    expect(readBudgetConfig({})).toEqual({
      dailyGlobalTokens: DEFAULT_DAILY_GLOBAL_TOKENS,
      dailyChatTokens: DEFAULT_DAILY_CHAT_TOKENS,
      maxOutputTokens: DEFAULT_MAX_OUTPUT_TOKENS,
    });
  });

  it("reads custom positive integer limits", () => {
    expect(
      readBudgetConfig({
        AI_DAILY_TOKEN_LIMIT: " 50000 ",
        AI_CHAT_DAILY_TOKEN_LIMIT: "8000",
        AI_MAX_OUTPUT_TOKENS: "512",
      }),
    ).toEqual({
      dailyGlobalTokens: 50_000,
      dailyChatTokens: 8_000,
      maxOutputTokens: 512,
    });
  });

  it("rejects invalid budget values", () => {
    expect(() => readBudgetConfig({ AI_DAILY_TOKEN_LIMIT: "0" })).toThrow(
      /AI_DAILY_TOKEN_LIMIT must be a positive integer/,
    );
    expect(() => readBudgetConfig({ AI_CHAT_DAILY_TOKEN_LIMIT: "nope" })).toThrow(
      /AI_CHAT_DAILY_TOKEN_LIMIT must be a positive integer/,
    );
  });
});

describe("logging", () => {
  it("removes a bot token from log output", () => {
    const lines: string[] = [];
    const stream = new Writable({
      write(chunk, _encoding, callback) {
        lines.push(chunk.toString());
        callback();
      },
    });
    const log = createLogger(stream);
    log.info(
      { botToken: sampleToken, note: `token ${sampleToken}` },
      `starting ${sampleToken}`,
    );
    log.error({ err: new Error(`request failed for ${sampleToken}`) }, "startup failed");

    const output = lines.join("");
    expect(output.includes(sampleToken)).toBe(false);
    expect(output.includes("[Redacted]")).toBe(true);
    expect(output.includes('"type":"Error"')).toBe(true);
  });

  it("removes AI API keys from log output", () => {
    const lines: string[] = [];
    const stream = new Writable({
      write(chunk, _encoding, callback) {
        lines.push(chunk.toString());
        callback();
      },
    });
    const log = createLogger(stream);
    log.info(
      {
        apiKey: sampleApiKey,
        AI_API_KEY: sampleAqKey,
        note: `Authorization: Bearer ${sampleApiKey}`,
      },
      `provider key ${sampleAqKey}`,
    );
    log.error(
      { err: new Error(`upstream rejected Bearer ${sampleApiKey}`) },
      "ai request failed",
    );

    const output = lines.join("");
    expect(output.includes(sampleApiKey)).toBe(false);
    expect(output.includes(sampleAqKey)).toBe(false);
    expect(output.includes("[Redacted]")).toBe(true);
  });
});

describe("redactSecrets", () => {
  it("replaces token-shaped text", () => {
    expect(redactSecrets(`failed ${sampleToken}`)).toBe("failed [Redacted]");
  });

  it("replaces Google-style API keys and bearer tokens", () => {
    expect(redactSecrets(`key ${sampleApiKey}`)).toBe("key [Redacted]");
    expect(redactSecrets(`key ${sampleAqKey}`)).toBe("key [Redacted]");
    expect(redactSecrets(`Authorization: Bearer ${sampleApiKey}`)).toBe(
      "Authorization: Bearer [Redacted]",
    );
  });
});
