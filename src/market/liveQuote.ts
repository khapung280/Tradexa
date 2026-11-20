import type { ForexPair } from "../risk/pairs.js";
import { BIQUOTE_PROVIDER_NAME, BiquoteLiveQuoteProvider } from "./biquote.js";

/** A verified quote older than this is rejected. This is not a market-closure rule. */
export const DEFAULT_QUOTE_MAX_AGE_MS = 15_000;

export type LiveQuote = {
  symbol: string;
  bid: number;
  ask: number;
  spreadPips: number;
  quotedAtMs: number;
  receivedAtMs: number;
  source: string;
};

export type LiveQuoteCode =
  | "unavailable"
  | "stale"
  | "invalid"
  | "future"
  | "timeout"
  | "auth"
  | "outage"
  | "closed";

export type LiveQuoteResult =
  | { ok: true; quote: LiveQuote }
  | { ok: false; code: LiveQuoteCode; reason: string };

export interface LiveQuoteProvider {
  readonly name: string;
  readonly configured: boolean;
  quote(symbol: string, nowMs: number): Promise<LiveQuoteResult>;
}

const CONFIGURATION_REASON = [
  "No verified live quote provider is configured.",
  "A future adapter needs LIVE_QUOTE_PROVIDER set to a registered provider name, plus that provider's own credentials.",
  "This repository does not include a live bid/ask adapter.",
  "Historical candles stay available and are not labelled live.",
  "This unavailable state is not a market-closure reading.",
].join(" ");

export function validateLiveQuote(input: {
  symbol: string;
  bid: number | null;
  ask: number | null;
  quotedAtMs: number | null;
  nowMs: number;
  source: string;
  pipSize: number;
  maxAgeMs?: number;
}): LiveQuoteResult {
  const maxAgeMs = input.maxAgeMs ?? DEFAULT_QUOTE_MAX_AGE_MS;
  if (input.bid === null || input.ask === null || input.quotedAtMs === null) {
    return { ok: false, code: "invalid", reason: "Bid, ask, and quote timestamp are all required." };
  }
  if (!Number.isFinite(input.bid) || !Number.isFinite(input.ask) || !Number.isFinite(input.quotedAtMs)) {
    return { ok: false, code: "invalid", reason: "Bid, ask, and quote timestamp must be finite numbers." };
  }
  if (!(input.bid > 0) || !(input.ask > 0)) {
    return { ok: false, code: "invalid", reason: "Bid and ask must be positive." };
  }
  if (!(input.ask > input.bid)) {
    return { ok: false, code: "invalid", reason: "Ask must be above bid. A missing or crossed quote is rejected." };
  }
  if (!(input.pipSize > 0)) {
    return { ok: false, code: "invalid", reason: "Pip size must be positive before a spread can be calculated." };
  }
  if (input.quotedAtMs > input.nowMs + 2_000) {
    return { ok: false, code: "future", reason: "Quote timestamp is in the future." };
  }
  const ageMs = input.nowMs - input.quotedAtMs;
  if (ageMs > maxAgeMs) {
    return { ok: false, code: "stale", reason: "Quote is older than the freshness limit." };
  }
  const spreadPips = (input.ask - input.bid) / input.pipSize;
  if (!Number.isFinite(spreadPips) || spreadPips <= 0) {
    return { ok: false, code: "invalid", reason: "Spread could not be calculated from bid and ask." };
  }
  return {
    ok: true,
    quote: {
      symbol: input.symbol,
      bid: input.bid,
      ask: input.ask,
      spreadPips,
      quotedAtMs: input.quotedAtMs,
      receivedAtMs: input.nowMs,
      source: input.source,
    },
  };
}

export class UnavailableLiveQuoteProvider implements LiveQuoteProvider {
  readonly configured = false;

  constructor(
    readonly name = "none",
    private readonly reason = CONFIGURATION_REASON,
  ) {}

  async quote(): Promise<LiveQuoteResult> {
    return { ok: false, code: "unavailable", reason: this.reason };
  }
}

export class RejectingLiveQuoteProvider implements LiveQuoteProvider {
  readonly configured = true;

  constructor(
    readonly name: string,
    private readonly code: Exclude<LiveQuoteCode, "unavailable">,
    private readonly reason: string,
  ) {}

  async quote(): Promise<LiveQuoteResult> {
    return { ok: false, code: this.code, reason: this.reason };
  }
}

export function createLiveQuoteProvider(env: NodeJS.ProcessEnv = process.env): LiveQuoteProvider {
  const requested = env.LIVE_QUOTE_PROVIDER?.trim() ?? "";
  if (requested.length === 0) {
    return new UnavailableLiveQuoteProvider();
  }
  if (requested.toLowerCase() === BIQUOTE_PROVIDER_NAME) {
    return new BiquoteLiveQuoteProvider();
  }
  return new UnavailableLiveQuoteProvider(
    "unregistered",
    "LIVE_QUOTE_PROVIDER is set, but this build has no registered live bid/ask adapter. No quote was requested. This is not a market-closure reading.",
  );
}

export function quoteSpread(pair: ForexPair, bid: number, ask: number): number {
  return (ask - bid) / pair.pipSize;
}
