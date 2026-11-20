import { describe, expect, it } from "vitest";
import {
  createLiveQuoteProvider,
  RejectingLiveQuoteProvider,
  UnavailableLiveQuoteProvider,
  validateLiveQuote,
} from "../src/market/liveQuote.ts";
import { BiquoteLiveQuoteProvider, parseBiquoteTick } from "../src/market/biquote.ts";
import { SHADOW_WAIT_LABEL } from "../src/decision/record.ts";
import { runDecision } from "../src/decision/run.ts";
import type { InstrumentAnalysis, TimeframeSection } from "../src/analysis/technical.ts";

const NOW = Date.UTC(2026, 10, 20, 12, 0, 0);

describe("live quotes", () => {
  it("calculates spread and rejects missing, crossed, stale, and future quotes", () => {
    const accepted = validateLiveQuote({
      symbol: "EURUSD",
      bid: 1.1,
      ask: 1.1001,
      quotedAtMs: NOW - 1_000,
      nowMs: NOW,
      source: "fixture",
      pipSize: 0.0001,
    });
    expect(accepted.ok).toBe(true);
    if (accepted.ok) {
      expect(accepted.quote.spreadPips).toBeCloseTo(1, 8);
    }
    expect(validateLiveQuote({ symbol: "EURUSD", bid: null, ask: 1.1, quotedAtMs: NOW, nowMs: NOW, source: "fixture", pipSize: 0.0001 }).ok).toBe(false);
    expect(validateLiveQuote({ symbol: "EURUSD", bid: 1.2, ask: 1.1, quotedAtMs: NOW, nowMs: NOW, source: "fixture", pipSize: 0.0001 }).code).toBe("invalid");
    expect(validateLiveQuote({ symbol: "EURUSD", bid: 1.1, ask: 1.1001, quotedAtMs: NOW - 60_000, nowMs: NOW, source: "fixture", pipSize: 0.0001 }).code).toBe("stale");
    expect(validateLiveQuote({ symbol: "EURUSD", bid: 1.1, ask: 1.1001, quotedAtMs: NOW + 10_000, nowMs: NOW, source: "fixture", pipSize: 0.0001 }).code).toBe("future");
  });

  it("keeps an unconfigured provider from looking like a market closure", async () => {
    const provider = createLiveQuoteProvider({});
    expect(provider.configured).toBe(false);
    const result = await provider.quote("EURUSD", NOW);
    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.code).toBe("unavailable");
      expect(result.reason).toContain("not a market-closure");
    }
    const named = createLiveQuoteProvider({ LIVE_QUOTE_PROVIDER: "unverified-feed" });
    const namedResult = await named.quote("EURUSD", NOW);
    expect(namedResult.ok).toBe(false);
    if (!namedResult.ok) {
      expect(namedResult.reason).not.toContain("unverified-feed");
    }
  });

  it("accepts a fresh biquote tick and rejects closed, stale, and malformed ticks", async () => {
    const fresh = parseBiquoteTick({
      symbol: "EURUSD",
      bid: 1.1,
      ask: 1.1002,
      timestamp: new Date(NOW - 1_000).toISOString(),
      marketState: "open",
      stale: false,
    }, "EURUSD", NOW);
    expect(fresh.ok).toBe(true);
    if (fresh.ok) {
      expect(fresh.quote.source).toBe("biquote");
      expect(fresh.quote.spreadPips).toBeCloseTo(2, 8);
    }
    expect(parseBiquoteTick({ symbol: "EURUSD", bid: 1.1, ask: 1.1002, timestamp: new Date(NOW).toISOString(), marketState: "closed", stale: false }, "EURUSD", NOW).code).toBe("closed");
    expect(parseBiquoteTick({ symbol: "EURUSD", bid: 1.1, ask: 1.1002, timestamp: new Date(NOW).toISOString(), marketState: "open", stale: true }, "EURUSD", NOW).code).toBe("stale");
    expect(parseBiquoteTick({ symbol: "GBPUSD", bid: 1.1, ask: 1.1002, timestamp: new Date(NOW).toISOString(), marketState: "open", stale: false }, "EURUSD", NOW).code).toBe("invalid");
    expect(parseBiquoteTick({ symbol: "EURUSD", ask: 1.1, timestamp: new Date(NOW).toISOString(), marketState: "open" }, "EURUSD", NOW).code).toBe("invalid");
    expect(parseBiquoteTick({ symbol: "EURUSD", bid: 0, ask: 1.1, timestamp: new Date(NOW).toISOString(), marketState: "open" }, "EURUSD", NOW).code).toBe("invalid");
    expect(parseBiquoteTick({ symbol: "EURUSD", bid: 1.2, ask: 1.1, timestamp: new Date(NOW).toISOString(), marketState: "open" }, "EURUSD", NOW).code).toBe("invalid");
    expect(parseBiquoteTick([], "EURUSD", NOW).code).toBe("invalid");

    const provider = new BiquoteLiveQuoteProvider(async () => new Response(JSON.stringify({
      symbol: "EURUSD",
      bid: 1.1,
      ask: 1.1001,
      timestamp: new Date(NOW - 500).toISOString(),
      marketState: "open",
      stale: false,
    }), { status: 200 }));
    const accepted = await provider.quote("EURUSD", NOW);
    expect(accepted.ok).toBe(true);
    const missing = new BiquoteLiveQuoteProvider(async () => new Response("", { status: 404 }));
    expect((await missing.quote("EURUSD", NOW)).code).toBe("stale");
    const limited = new BiquoteLiveQuoteProvider(async () => new Response("", { status: 429 }));
    expect((await limited.quote("EURUSD", NOW)).code).toBe("outage");
    expect(createLiveQuoteProvider({ LIVE_QUOTE_PROVIDER: "biquote" }).name).toBe("biquote");
    const quoted = await runDecision({
      symbol: "EURUSD",
      nowMs: NOW,
      performanceCatalog: null,
      analyze: () => report(),
      liveQuotes: provider,
    });
    expect(quoted.liveQuote.status).toBe("accepted");
    expect(quoted.finalLabel).toBe(SHADOW_WAIT_LABEL);
    expect(quoted.actionable).toBe(false);
    expect(quoted.quotePlan.entry).toBe("NOT AVAILABLE");
    expect(quoted.levels.entry).toBe("NOT AVAILABLE");
    expect(quoted.levels.reason).not.toContain("No verified live bid");
  });

  it("does not turn a provider outage, timeout, or auth failure into a trade", async () => {
    for (const code of ["outage", "timeout", "auth"] as const) {
      const result = await runDecision({
        symbol: "EURUSD",
        nowMs: NOW,
        performanceCatalog: null,
        analyze: () => report(),
        liveQuotes: new RejectingLiveQuoteProvider("fixture", code, `${code} fixture`),
      });
      expect(result.finalClassification).toBe("WAIT");
      expect(result.finalLabel).toBe("WAIT — LIVE MARKET DATA UNAVAILABLE");
      expect(result.actionable).toBe(false);
      expect(result.liveQuote.code).toBe(code);
      expect(result.quotePlan.entry).toBe("NOT AVAILABLE");
      expect(result.levels.reason).toContain("No verified live bid");
    }
    const unavailable = new UnavailableLiveQuoteProvider();
    expect((await unavailable.quote()).code).toBe("unavailable");
  });
});

function report(): InstrumentAnalysis {
  const section = (timeframe: "H1" | "H4"): TimeframeSection => ({
    status: "ok",
    timeframe,
    candleOpenMs: NOW - 3_600_000,
    candleCloseMs: NOW - 1_000,
    close: 1.1,
    candlesUsed: 250,
    filesRead: 1,
    indicators: {
      ema20: 3, ema50: 2, ema200: 1, emaCrossover: "none", rsi14: 50,
      macd: 1, macdSignal: 0, macdHistogram: 1, macdCrossover: "none", atr14: 0.001, volatility: "typical",
    },
    trend: { classification: "bullish", evidence: [], facts: { ema20: 3, ema50: 2, ema200: 1, close: 1.1, rsi: 50, atr: 0.001, slope: 1 } },
    structure: { state: "insufficient_data", swings: [], lastHighLabel: null, lastLowLabel: null, note: "" },
    zones: {
      support: [], resistance: [], historicalSwingLows: [], historicalSwingHighs: [],
      omitted: { support: 0, resistance: 0, historicalSwingLows: 0, historicalSwingHighs: 0 },
      tolerance: null, emptySupportReason: "", emptyResistanceReason: "", note: "",
    },
    warnings: [],
  });
  return { kind: "report", symbol: "EURUSD", asOfMs: NOW, sections: [section("H1"), section("H4")], summary: [] };
}
