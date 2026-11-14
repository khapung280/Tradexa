export const CONVERSION_STATUSES = ["not_required", "required"] as const;
export type ConversionStatus = (typeof CONVERSION_STATUSES)[number];

export const RISK_ERROR_CODES = [
  "invalid_balance",
  "invalid_risk_percent",
  "invalid_stop_loss_pips",
  "unknown_pair",
  "conversion_required",
  "invalid_conversion_rate",
  "risk_percent_limit",
  "lot_size_limit",
] as const;
export type RiskErrorCode = (typeof RISK_ERROR_CODES)[number];

export type RiskRequest = {
  accountBalance: number;
  riskPercent: number;
  pair: string;
  stopLossPips: number;
  accountCurrency?: string;
  quoteToAccountRate?: number;
};

export type RiskSuccess = {
  ok: true;
  pair: string;
  base: string;
  quote: string;
  pipSize: number;
  unitsPerLot: number;
  accountBalance: number;
  riskPercent: number;
  stopLossPips: number;
  riskAmount: number;
  pipValueQuotePerLot: number;
  pipValueAccountPerLot: number;
  pipValueCurrency: string;
  positionSizeLots: number;
  quoteToAccountRate: number;
  conversion: ConversionStatus;
  accountCurrency: string;
};

export type RiskFailure = {
  ok: false;
  error: RiskErrorCode;
};

export type RiskResult = RiskSuccess | RiskFailure;
