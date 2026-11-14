export const DEFAULT_MAX_RISK_PERCENT = 2;
export const DEFAULT_MAX_LOTS = 2;
export const DEFAULT_MAX_DAILY_LOSS = 100;

export type RiskLimits = {
  maxRiskPercent: number;
  maxLots: number;
  maxDailyLoss: number;
};

export const DEFAULT_RISK_LIMITS: RiskLimits = {
  maxRiskPercent: DEFAULT_MAX_RISK_PERCENT,
  maxLots: DEFAULT_MAX_LOTS,
  maxDailyLoss: DEFAULT_MAX_DAILY_LOSS,
};
