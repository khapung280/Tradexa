import { describe, expect, it } from "vitest";
import { renderLearnReply } from "../src/commands/learn.ts";
import {
  EDUCATION_DISCLAIMER,
  EDUCATION_TOPIC_IDS,
  EDUCATION_TOPICS,
  formatTopic,
  formatTopicList,
} from "../src/education/topics.ts";

const SIGNAL_PATTERNS = [
  /\bbuy now\b/i,
  /\bsell now\b/i,
  /\byou should (buy|sell)\b/i,
  /\benter (a |the )?trade\b/i,
  /\btake profit\b/i,
  /\bstop[- ]loss\b/i,
  /\bguaranteed (profit|return|win)\b/i,
];

function allEducationText(): string {
  return [
    formatTopicList(),
    ...EDUCATION_TOPICS.map((topic) => formatTopic(topic)),
  ].join("\n");
}

describe("renderLearnReply", () => {
  it("lists every topic when no id is given", () => {
    const reply = renderLearnReply("");
    expect(reply).toBe(formatTopicList());
    for (const id of EDUCATION_TOPIC_IDS) {
      expect(reply).toContain(`/learn ${id}`);
    }
    expect(reply).toContain(EDUCATION_DISCLAIMER);
  });

  it("shows the selected topic", () => {
    const reply = renderLearnReply(" pips ");
    const pips = EDUCATION_TOPICS.find((topic) => topic.id === "pips");
    expect(pips).toBeDefined();
    if (pips === undefined) {
      return;
    }
    expect(reply).toBe(formatTopic(pips));
    expect(reply).toContain("Pips");
    expect(reply).toContain("0.0001");
    expect(reply).toContain(EDUCATION_DISCLAIMER);
    expect(reply).not.toContain("/learn lots");
  });

  it("shows the topic list for an unknown id", () => {
    const reply = renderLearnReply("candlesticks");
    expect(reply.startsWith('I don\'t have a lesson named "candlesticks".')).toBe(true);
    expect(reply).toContain(formatTopicList());
    for (const id of EDUCATION_TOPIC_IDS) {
      expect(reply).toContain(`/learn ${id}`);
    }
  });
});

describe("education catalog", () => {
  it("includes the required topic ids", () => {
    expect(EDUCATION_TOPICS.map((topic) => topic.id)).toEqual([...EDUCATION_TOPIC_IDS]);
  });

  it("does not include trading-signal language", () => {
    const text = allEducationText();
    for (const pattern of SIGNAL_PATTERNS) {
      expect(text, String(pattern)).not.toMatch(pattern);
    }
    expect(text).toContain("does not give trading signals");
  });
});
