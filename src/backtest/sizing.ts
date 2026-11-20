import { findPair, type ForexPair } from "../risk/pairs.js";
import type { Sizing } from "./types.js";

export function pipValuePerLot(pair: ForexPair, quoteToAccountRate: number): number {
  return pair.unitsPerLot * pair.pipSize * quoteToAccountRate;
}

/**
 * Pips of cash loss on a stop that is touched, not gapped.
 * A buy pays the spread on entry. A short stop is an ask price, so the spread
 * shifts the trigger and is not charged again. Both sides pay entry and exit slippage.
 * A gap through the stop can lose more than this.
 */
export function cleanStopPips(input: {
  side: "buy" | "sell";
  stopLossPips: number;
  spreadPips: number;
  slippagePips: number;
}): number {
  const slippage = input.slippagePips * 2;
  if (input.side === "buy") {
    return input.stopLossPips + input.spreadPips + slippage;
  }
  return input.stopLossPips + slippage;
}

export function positionLots(input: {
  balance: number;
  sizing: Sizing;
  stopLossPips: number;
  pipValuePerLot: number;
  minLots: number;
  maxLots: number;
  side?: "buy" | "sell";
  spreadPips?: number;
  slippagePips?: number;
  commissionPerSidePerLot?: number;
}): { ok: true; lots: number } | { ok: false; reason: string } {
  if (!(input.balance > 0)) {
    return { ok: false, reason: "Balance is not positive, so no new position is opened." };
  }
  if (!(input.stopLossPips > 0) || !(input.pipValuePerLot > 0)) {
    return { ok: false, reason: "Stop-loss pips and pip value must be positive." };
  }
  const spreadPips = input.spreadPips ?? 0;
  const slippagePips = input.slippagePips ?? 0;
  const commission = input.commissionPerSidePerLot ?? 0;
  if (spreadPips < 0 || slippagePips < 0 || commission < 0) {
    return { ok: false, reason: "Spread, slippage, and commission cannot be negative." };
  }
  const adversePips = cleanStopPips({
    side: input.side ?? "buy",
    stopLossPips: input.stopLossPips,
    spreadPips,
    slippagePips,
  });
  const riskCashPerLot = adversePips * input.pipValuePerLot + commission * 2;
  const lots = input.sizing.mode === "fixed"
    ? input.sizing.lots
    : (input.balance * (input.sizing.riskPercent / 100)) / riskCashPerLot;
  if (!Number.isFinite(lots) || lots <= 0) {
    return { ok: false, reason: "Position size is not a positive number." };
  }
  if (lots < input.minLots) {
    return { ok: false, reason: "Position size is below the configured minimum lot." };
  }
  if (lots > input.maxLots) {
    return { ok: false, reason: "Position size is above the configured maximum lot." };
  }
  return { ok: true, lots };
}

export function resolveQuoteRate(
  symbol: string,
  accountCurrency: string,
  suppliedRate: number | null,
): { ok: true; pair: ForexPair; rate: number } | { ok: false; reason: string } {
  const pair = findPair(symbol);
  if (pair === undefined) {
    return { ok: false, reason: `${symbol} is not a supported instrument.` };
  }
  const currency = accountCurrency.trim().toUpperCase();
  if (currency.length === 0) {
    return { ok: false, reason: "Account currency is required." };
  }
  if (currency === pair.quote) {
    return { ok: true, pair, rate: 1 };
  }
  if (suppliedRate === null) {
    return {
      ok: false,
      reason: `${pair.symbol} is quoted in ${pair.quote}. A positive ${pair.quote}-to-${currency} rate is required. No monetary result was estimated.`,
    };
  }
  if (!Number.isFinite(suppliedRate) || suppliedRate <= 0) {
    return { ok: false, reason: "The quote-to-account rate must be a positive number." };
  }
  return { ok: true, pair, rate: suppliedRate };
}
