import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import type { AiProvider } from "../src/ai/types.ts";
import { openChatStore } from "../src/db/store.ts";
import { runDecision } from "../src/decision/run.ts";
import { LIVE_WAIT_LABEL, RESEARCH_LABEL } from "../src/decision/record.ts";

const NOW = Date.UTC(2026, 10, 20, 12, 0, 0);

const provider: AiProvider = {
  name: "fixture",
  model: "fixture-model",
  chat: async () => ({
    text: JSON.stringify({
      schemaVersion: 1,
      marketBias: "NEUTRAL",
      evidenceIds: ["DATA_HISTORICAL_NOT_LIVE"],
      supportingReasons: [],
      contradictingReasons: [],
      missingEvidence: [],
      riskObservations: [],
      assessmentSummary: "The supplied evidence is historical.",
      recommendedClassification: "WAIT",
      limitations: ["No live quote was supplied."],
    }),
    inputTokens: 1,
    outputTokens: 1,
  }),
};

describe("decision history on native EURUSD", () => {
  const dirs: string[] = [];
  afterEach(() => {
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("reads closed native candles and stays non-actionable", async () => {
    const dir = mkdtempSync(join(tmpdir(), "tradexa-decision-history-"));
    dirs.push(dir);
    const store = openChatStore(join(dir, "tradexa.db"));
    const record = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      chatId: 9,
      store,
      provider,
    });
    const h1 = record.snapshot.timeframes.find((item) => item.timeframe === "H1");
    expect(record.snapshot.mode).toBe("HISTORICAL");
    expect(record.snapshot.source).toContain("Dukascopy");
    expect(h1?.closed).toBe(true);
    expect(h1?.latestCloseMs).not.toBeNull();
    expect((h1?.latestCloseMs ?? 0) < NOW).toBe(true);
    expect(h1?.freshness).toBe("historical");
    expect(record.finalLabel).toBe(LIVE_WAIT_LABEL);
    expect(record.research.label).toBe(RESEARCH_LABEL);
    expect(record.actionable).toBe(false);
    expect(record.evidence.some((item) => item.id.startsWith("TREND_"))).toBe(true);
    expect(record.ai.status).toBe("accepted");
    if (record.ai.status === "accepted") {
      expect(record.evidence.map((item) => item.id)).toEqual(expect.arrayContaining(record.ai.assessment.evidenceIds));
    }
    expect(store.getDecision(record.id)?.id).toBe(record.id);
    store.close();
  }, 60_000);
});
