import { findPair } from "../risk/pairs.js";
import { validateLiveQuote, type LiveQuoteProvider, type LiveQuoteResult } from "./liveQuote.js";

export const BIQUOTE_PROVIDER_NAME = "biquote";

export type QuoteFetch = (url: string, init?: RequestInit) => Promise<Response>;

export function parseBiquoteTick(body: unknown, symbol: string, nowMs: number): LiveQuoteResult {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return { ok: false, code: "invalid", reason: "The quote provider returned an unexpected response." };
  }
  const record = body as Record<string, unknown>;
  if (record.marketState === "closed") {
    return { ok: false, code: "closed", reason: "The quote provider reports the market closed. This is not a provider outage." };
  }
  if (record.stale === true) {
    return { ok: false, code: "stale", reason: "The quote provider marked the quote stale." };
  }
  if (record.symbol !== symbol) {
    return { ok: false, code: "invalid", reason: "The quote symbol did not match the request." };
  }
  const pair = findPair(symbol);
  if (pair === undefined) {
    return { ok: false, code: "invalid", reason: "No pip size is known for this symbol." };
  }
  const quotedAtMs = typeof record.timestamp === "string" ? Date.parse(record.timestamp) : Number.NaN;
  return validateLiveQuote({
    symbol,
    bid: typeof record.bid === "number" ? record.bid : null,
    ask: typeof record.ask === "number" ? record.ask : null,
    quotedAtMs: Number.isFinite(quotedAtMs) ? quotedAtMs : null,
    nowMs,
    source: BIQUOTE_PROVIDER_NAME,
    pipSize: pair.pipSize,
  });
}

export class BiquoteLiveQuoteProvider implements LiveQuoteProvider {
  readonly name = BIQUOTE_PROVIDER_NAME;
  readonly configured = true;

  constructor(private readonly fetchImpl: QuoteFetch = fetch) {}

  async quote(symbol: string, nowMs: number): Promise<LiveQuoteResult> {
    if (symbol !== "EURUSD") {
      return { ok: false, code: "invalid", reason: "The live quote adapter is registered for EURUSD only." };
    }
    let response: Response;
    try {
      response = await this.fetchImpl(`https://biquote.io/api/${symbol}?allowStale=false`, {
        signal: AbortSignal.timeout(8_000),
      });
    } catch (error: unknown) {
      const name = error instanceof Error ? error.name : "";
      if (name === "AbortError" || name === "TimeoutError") {
        return { ok: false, code: "timeout", reason: "The quote provider timed out. This is not a market-closure reading." };
      }
      return { ok: false, code: "outage", reason: "The quote provider could not be reached. This is not a market-closure reading." };
    }
    if (response.status === 401 || response.status === 403) {
      return { ok: false, code: "auth", reason: "The quote provider refused the request." };
    }
    if (response.status === 404) {
      return { ok: false, code: "stale", reason: "The quote provider has no current EURUSD quote. This is not a market-closure reading." };
    }
    if (response.status === 429 || response.status >= 500) {
      return { ok: false, code: "outage", reason: "The quote provider is unavailable. This is not a market-closure reading." };
    }
    if (!response.ok) {
      return { ok: false, code: "invalid", reason: "The quote provider returned an unexpected response." };
    }
    try {
      return parseBiquoteTick(await response.json(), symbol, nowMs);
    } catch {
      return { ok: false, code: "invalid", reason: "The quote provider returned an unexpected response." };
    }
  }
}
