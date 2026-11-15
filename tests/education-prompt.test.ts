import { describe, expect, it } from "vitest";
import { buildChatSystemPrompt } from "../src/chat/process.ts";
import { buildEducationSystemPrompt } from "../src/education/prompt.ts";
import { buildMarketAiContext } from "../src/market/context.ts";
import { EDUCATION_DISCLAIMER, EDUCATION_TOPIC_IDS } from "../src/education/topics.ts";

const FROZEN_NOW = Date.parse("2026-01-12T14:00:00.000Z");

describe("buildEducationSystemPrompt", () => {
  it("includes all eight topic ids and the education disclaimer", () => {
    const prompt = buildEducationSystemPrompt();
    expect(prompt).toContain(EDUCATION_DISCLAIMER);
    for (const id of EDUCATION_TOPIC_IDS) {
      expect(prompt).toContain(`[${id}]`);
    }
  });

  it("includes the required safety rules", () => {
    const prompt = buildEducationSystemPrompt();
    expect(prompt).toMatch(/Prefer the catalog/i);
    expect(prompt).toMatch(/Never invent live prices/i);
    expect(prompt).toMatch(/support or resistance levels/i);
    expect(prompt).toMatch(/candles/i);
    expect(prompt).toMatch(/Never provide buy\/sell signals/i);
    expect(prompt).toMatch(/trade entries/i);
    expect(prompt).toMatch(/guaranteed outcomes/i);
    expect(prompt).toMatch(/Live market data is not connected/i);
    expect(prompt).toContain("/analyze");
    expect(prompt).toContain("/learn");
  });

  it("is included in the per-request AI chat system prompt", () => {
    const prompt = buildChatSystemPrompt(FROZEN_NOW);
    expect(prompt.startsWith(buildEducationSystemPrompt())).toBe(true);
    expect(prompt).toContain(buildMarketAiContext(FROZEN_NOW));
  });
});
