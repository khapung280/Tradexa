import { DEFAULT_RISK_LIMITS, type RiskLimits } from "./limits.js";
import { findPair } from "./pairs.js";
import type { ConversionStatus, RiskRequest, RiskResult } from "./types.js";

function isPositiveFinite(value: number): boolean {
  return Number.isFinite(value) && value > 0;
}

function readAccountCurrency(request: RiskRequest): string | null {
  if (!("accountCurrency" in request) || request.accountCurrency === undefined) {
    return null;
  }
  const currency = request.accountCurrency.trim().toUpperCase();
  return currency.length > 0 ? currency : null;
}

function readQuoteToAccountRate(request: RiskRequest): number | null {
  if (!("quoteToAccountRate" in request) || request.quoteToAccountRate === undefined) {
    return null;
  }
  return request.quoteToAccountRate;
}

function conversionStatus(
  accountCurrency: string,
  quote: string,
): ConversionStatus {
  return accountCurrency === quote ? "not_required" : "required";
}

export function calculateRisk(
  request: RiskRequest,
  limits: RiskLimits = DEFAULT_RISK_LIMITS,
): RiskResult {
  if (!isPositiveFinite(request.accountBalance)) {
    return { ok: false, error: "invalid_balance" };
  }
  if (!isPositiveFinite(request.riskPercent)) {
    return { ok: false, error: "invalid_risk_percent" };
  }
  if (!isPositiveFinite(request.stopLossPips)) {
    return { ok: false, error: "invalid_stop_loss_pips" };
  }
  if (request.riskPercent > limits.maxRiskPercent) {
    return { ok: false, error: "risk_percent_limit" };
  }

  const pair = findPair(request.pair);
  if (pair === undefined) {
    return { ok: false, error: "unknown_pair" };
  }

  const accountCurrency = readAccountCurrency(request);
  if (accountCurrency === null) {
    return { ok: false, error: "conversion_required" };
  }

  const conversion = conversionStatus(accountCurrency, pair.quote);
  const suppliedRate = readQuoteToAccountRate(request);

  let quoteToAccountRate: number;
  if (conversion === "not_required") {
    quoteToAccountRate = 1;
  } else if (suppliedRate === null) {
    return { ok: false, error: "conversion_required" };
  } else if (!isPositiveFinite(suppliedRate)) {
    return { ok: false, error: "invalid_conversion_rate" };
  } else {
    quoteToAccountRate = suppliedRate;
  }

  const pipValueQuotePerLot = pair.unitsPerLot * pair.pipSize;
  const pipValueAccountPerLot = pipValueQuotePerLot * quoteToAccountRate;
  const riskAmount = request.accountBalance * (request.riskPercent / 100);
  const positionSizeLots = riskAmount / (request.stopLossPips * pipValueAccountPerLot);
  if (positionSizeLots > limits.maxLots) {
    return { ok: false, error: "lot_size_limit" };
  }

  return {
    ok: true,
    pair: pair.symbol,
    base: pair.base,
    quote: pair.quote,
    pipSize: pair.pipSize,
    unitsPerLot: pair.unitsPerLot,
    accountBalance: request.accountBalance,
    riskPercent: request.riskPercent,
    stopLossPips: request.stopLossPips,
    riskAmount,
    pipValueQuotePerLot,
    pipValueAccountPerLot,
    pipValueCurrency: pair.quote,
    positionSizeLots,
    quoteToAccountRate,
    conversion,
    accountCurrency,
  };
}
