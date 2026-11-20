import { createHash } from "node:crypto";

export const DECISION_SCHEMA_VERSION = 1;
export const ASSESSMENT_SCHEMA_VERSION = 1;
export const ASSESSMENT_PROMPT_VERSION = "phase9-assessment-v4";
export const OPERATING_MODE = "shadow" as const;
export const LIVE_WAIT_LABEL = "WAIT — LIVE MARKET DATA UNAVAILABLE";
export const SHADOW_WAIT_LABEL = "WAIT — SHADOW MODE";
export const RESEARCH_LABEL = "HISTORICAL RESEARCH ONLY — NOT A LIVE TRADING SIGNAL";
export const LEVELS_UNAVAILABLE =
  "NOT AVAILABLE. No verified live bid or ask, and no verified account equity. A historical candle close is not a current executable price.";

export const CLASSIFICATIONS = ["BUY", "SELL", "WAIT"] as const;
export type Classification = (typeof CLASSIFICATIONS)[number];

export const MARKET_BIASES = ["BULLISH", "BEARISH", "NEUTRAL"] as const;
export type MarketBias = (typeof MARKET_BIASES)[number];

export type EvidencePolarity = "bullish" | "bearish" | "neutral" | "warning";

export type EvidenceItem = {
  id: string;
  polarity: EvidencePolarity;
  text: string;
};

export type TimeframeSnapshot = {
  timeframe: string;
  latestOpenMs: number | null;
  latestCloseMs: number | null;
  closed: boolean;
  availableCount: number;
  requiredCount: number;
  completeness: "complete" | "insufficient" | "unavailable";
  gapQuality: "none_observed" | "unclassified_gap" | "unknown";
  freshness: "historical" | "fresh" | "stale" | "unavailable";
  ageMs: number | null;
  limitations: string[];
};

export type MarketSnapshot = {
  symbol: string;
  source: string;
  mode: "HISTORICAL" | "LIVE";
  createdAtMs: number;
  timeframes: TimeframeSnapshot[];
  spread: {
    available: false;
    source: "unavailable";
    reason: string;
  };
  liveSourceVerified: false;
  limitations: string[];
};

export type StrategyReference = {
  strategyId: string;
  timeframe: string;
  startMs: number;
  endMs: number;
  sample: "full_sample" | "in_sample" | "out_of_sample";
  trades: number;
  netProfit: number;
  profitFactorBeforeCosts: number | null;
  maxDrawdown: number;
};

export type StrategyEvidence =
  | { available: false; reason: string }
  | {
      available: true;
      version: 1;
      symbol: string;
      disclaimer: string;
      references: StrategyReference[];
    };

export type LiveQuoteState = {
  status: "unavailable" | "rejected" | "accepted";
  code: string;
  detail: string;
  quotedAtMs: number | null;
  spreadPips: number | null;
  source: string;
};

export type QuotePlan = {
  entry: "NOT AVAILABLE";
  stopLoss: "NOT AVAILABLE";
  takeProfit: "NOT AVAILABLE";
  riskReward: "NOT AVAILABLE";
  positionSize: "NOT AVAILABLE";
  reason: string;
};

export type GateCheck = {
  id: string;
  passed: boolean;
  detail: string;
};

export type AiAssessment = {
  schemaVersion: 1;
  marketBias: MarketBias;
  evidenceIds: string[];
  supportingReasons: string[];
  contradictingReasons: string[];
  missingEvidence: string[];
  riskObservations: string[];
  assessmentSummary: string;
  recommendedClassification: Classification;
  limitations: string[];
};

export type AiAssessmentState =
  | { status: "not_called"; reason: string }
  | { status: "unavailable"; reason: string }
  | { status: "rejected"; reason: string }
  | { status: "accepted"; assessment: AiAssessment };

export type ResearchAssessment = {
  applicability: "NON_ACTIONABLE";
  label: typeof RESEARCH_LABEL;
  classification: Classification;
  reason: string;
  bullish: string[];
  bearish: string[];
  conflicts: string[];
};

export type DecisionRecord = {
  id: string;
  schemaVersion: 1;
  operatingMode: typeof OPERATING_MODE;
  symbol: string;
  createdAtMs: number;
  chatId: number | null;
  snapshot: MarketSnapshot;
  evidence: EvidenceItem[];
  strategyEvidence: StrategyEvidence;
  liveQuote: LiveQuoteState;
  quotePlan: QuotePlan;
  gates: GateCheck[];
  risk: {
    status: "blocked" | "not_verified";
    detail: string;
    userReportedLoss: number | null;
    killSwitch: boolean | null;
    accountEquity: "NOT VERIFIED";
    liveExposure: "NOT VERIFIED";
    brokerPnl: "NOT VERIFIED";
  };
  levels: {
    entry: "NOT AVAILABLE";
    stopLoss: "NOT AVAILABLE";
    takeProfit: "NOT AVAILABLE";
    positionSize: "NOT AVAILABLE";
    reason: string;
  };
  ai: AiAssessmentState;
  research: ResearchAssessment;
  finalClassification: "WAIT";
  finalLabel: typeof LIVE_WAIT_LABEL | typeof SHADOW_WAIT_LABEL;
  actionable: false;
  waitReasons: string[];
  fingerprint: string;
  provider: { name: string; model: string } | null;
  promptVersion: typeof ASSESSMENT_PROMPT_VERSION;
};

const POLARITIES = new Set<EvidencePolarity>(["bullish", "bearish", "neutral", "warning"]);

export function decisionFingerprint(input: {
  symbol: string;
  mode: MarketSnapshot["mode"];
  timeframes: readonly TimeframeSnapshot[];
  evidenceIds: readonly string[];
}): string {
  const timeframes = [...input.timeframes]
    .map((item) => ({
      timeframe: item.timeframe,
      latestCloseMs: item.latestCloseMs,
      completeness: item.completeness,
      gapQuality: item.gapQuality,
    }))
    .sort((left, right) => left.timeframe.localeCompare(right.timeframe));
  const body = JSON.stringify({
    schemaVersion: DECISION_SCHEMA_VERSION,
    symbol: input.symbol,
    mode: input.mode,
    timeframes,
    evidenceIds: [...input.evidenceIds].sort(),
  });
  return createHash("sha256").update(body).digest("hex");
}

export function parseStoredDecision(
  value: unknown,
): { ok: true; record: DecisionRecord } | { ok: false; reason: string } {
  if (!isRecord(value)) {
    return { ok: false, reason: "Decision record must be an object." };
  }
  if (value.schemaVersion !== DECISION_SCHEMA_VERSION) {
    return { ok: false, reason: "Decision schema version is not supported." };
  }
  if (value.operatingMode !== OPERATING_MODE) {
    return { ok: false, reason: "Decision operating mode must be shadow." };
  }
  if (value.actionable !== false || value.finalClassification !== "WAIT" || (value.finalLabel !== LIVE_WAIT_LABEL && value.finalLabel !== SHADOW_WAIT_LABEL)) {
    return { ok: false, reason: "A Phase 9 decision cannot be stored as an actionable BUY or SELL." };
  }
  if (typeof value.id !== "string" || value.id.length === 0 || value.id.length > 80) {
    return { ok: false, reason: "Decision id is invalid." };
  }
  if (typeof value.symbol !== "string" || value.symbol.length === 0) {
    return { ok: false, reason: "Decision symbol is invalid." };
  }
  if (typeof value.createdAtMs !== "number" || !Number.isFinite(value.createdAtMs)) {
    return { ok: false, reason: "Decision timestamp is invalid." };
  }
  if (value.chatId !== null && (typeof value.chatId !== "number" || !Number.isSafeInteger(value.chatId))) {
    return { ok: false, reason: "Decision chat id is invalid." };
  }
  if (typeof value.fingerprint !== "string" || !/^[a-f0-9]{64}$/.test(value.fingerprint)) {
    return { ok: false, reason: "Decision fingerprint is invalid." };
  }
  if (!isSnapshot(value.snapshot) || !isResearch(value.research) || !isAiState(value.ai)) {
    return { ok: false, reason: "Decision evidence payload is invalid." };
  }
  if (!Array.isArray(value.evidence) || !value.evidence.every(isEvidenceItem)) {
    return { ok: false, reason: "Decision evidence list is invalid." };
  }
  if (!Array.isArray(value.gates) || !value.gates.every(isGate)) {
    return { ok: false, reason: "Decision gates are invalid." };
  }
  if (!isRisk(value.risk) || !isLevels(value.levels) || !isStrategy(value.strategyEvidence) || !isLiveQuote(value.liveQuote) || !isQuotePlan(value.quotePlan)) {
    return { ok: false, reason: "Decision risk or strategy payload is invalid." };
  }
  if (!Array.isArray(value.waitReasons) || value.waitReasons.some((item) => typeof item !== "string")) {
    return { ok: false, reason: "Decision wait reasons are invalid." };
  }
  if (value.promptVersion !== ASSESSMENT_PROMPT_VERSION) {
    return { ok: false, reason: "Decision prompt version is not supported." };
  }
  if (!isProvider(value.provider)) {
    return { ok: false, reason: "Decision provider metadata is invalid." };
  }
  return { ok: true, record: value as DecisionRecord };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isEvidenceItem(value: unknown): value is EvidenceItem {
  if (!isRecord(value)) {
    return false;
  }
  return typeof value.id === "string"
    && POLARITIES.has(value.polarity as EvidencePolarity)
    && typeof value.text === "string";
}

function isGate(value: unknown): value is GateCheck {
  if (!isRecord(value)) {
    return false;
  }
  return typeof value.id === "string" && typeof value.passed === "boolean" && typeof value.detail === "string";
}

function isSnapshot(value: unknown): value is MarketSnapshot {
  if (!isRecord(value)) {
    return false;
  }
  return (value.mode === "HISTORICAL" || value.mode === "LIVE")
    && value.liveSourceVerified === false
    && typeof value.symbol === "string"
    && typeof value.createdAtMs === "number"
    && Array.isArray(value.timeframes);
}

function isResearch(value: unknown): value is ResearchAssessment {
  if (!isRecord(value)) {
    return false;
  }
  return value.applicability === "NON_ACTIONABLE"
    && value.label === RESEARCH_LABEL
    && (value.classification === "BUY" || value.classification === "SELL" || value.classification === "WAIT")
    && typeof value.reason === "string"
    && Array.isArray(value.bullish)
    && Array.isArray(value.bearish)
    && Array.isArray(value.conflicts);
}

function isAiState(value: unknown): value is AiAssessmentState {
  if (!isRecord(value) || typeof value.status !== "string") {
    return false;
  }
  if (value.status === "accepted") {
    return isAssessment(value.assessment);
  }
  return (value.status === "not_called" || value.status === "unavailable" || value.status === "rejected")
    && typeof value.reason === "string";
}

function isAssessment(value: unknown): value is AiAssessment {
  if (!isRecord(value)) {
    return false;
  }
  return value.schemaVersion === ASSESSMENT_SCHEMA_VERSION
    && (value.marketBias === "BULLISH" || value.marketBias === "BEARISH" || value.marketBias === "NEUTRAL")
    && (value.recommendedClassification === "BUY" || value.recommendedClassification === "SELL" || value.recommendedClassification === "WAIT")
    && Array.isArray(value.evidenceIds)
    && typeof value.assessmentSummary === "string";
}

function isRisk(value: unknown): boolean {
  if (!isRecord(value)) {
    return false;
  }
  return (value.status === "blocked" || value.status === "not_verified")
    && value.accountEquity === "NOT VERIFIED"
    && value.liveExposure === "NOT VERIFIED"
    && value.brokerPnl === "NOT VERIFIED";
}

function isLevels(value: unknown): boolean {
  if (!isRecord(value)) {
    return false;
  }
  return value.entry === "NOT AVAILABLE"
    && value.stopLoss === "NOT AVAILABLE"
    && value.takeProfit === "NOT AVAILABLE"
    && value.positionSize === "NOT AVAILABLE";
}

function isStrategy(value: unknown): value is StrategyEvidence {
  if (!isRecord(value)) {
    return false;
  }
  if (value.available === false) {
    return typeof value.reason === "string";
  }
  return value.available === true
    && value.version === 1
    && typeof value.symbol === "string"
    && typeof value.disclaimer === "string"
    && value.disclaimer.includes("not future performance")
    && Array.isArray(value.references);
}

function isLiveQuote(value: unknown): value is LiveQuoteState {
  if (!isRecord(value)) {
    return false;
  }
  return (value.status === "unavailable" || value.status === "rejected" || value.status === "accepted")
    && typeof value.code === "string"
    && typeof value.detail === "string"
    && typeof value.source === "string";
}

function isQuotePlan(value: unknown): value is QuotePlan {
  if (!isRecord(value)) {
    return false;
  }
  return value.entry === "NOT AVAILABLE"
    && value.stopLoss === "NOT AVAILABLE"
    && value.takeProfit === "NOT AVAILABLE"
    && value.riskReward === "NOT AVAILABLE"
    && value.positionSize === "NOT AVAILABLE"
    && typeof value.reason === "string";
}

function isProvider(value: unknown): value is DecisionRecord["provider"] {
  if (value === null) {
    return true;
  }
  return isRecord(value) && typeof value.name === "string" && typeof value.model === "string" && !/key|token|secret/i.test(JSON.stringify(value));
}
