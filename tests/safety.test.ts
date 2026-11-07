import { Writable } from "node:stream";
import { describe, expect, it } from "vitest";
import { readBotToken } from "../src/config/env.ts";
import { createLogger, redactSecrets } from "../src/config/logger.ts";

const sampleToken = `123456789:${"A".repeat(35)}`;

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
});

describe("redactSecrets", () => {
  it("replaces token-shaped text", () => {
    expect(redactSecrets(`failed ${sampleToken}`)).toBe("failed [Redacted]");
  });
});
