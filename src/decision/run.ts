import { randomUUID } from "node:crypto";
import type { AiProvider } from "../ai/types.js";
import { analyzeInstrument, type AnalysisOptions, type InstrumentAnalysis } from "../analysis/technical.js";
import type { TokenBudget } from "../budget/ledger.js";
import { classifyAiError, AI_TIMEOUT_MS } from "../chat/process.js";
import type { ChatStore } from "../db/store.js";
import { createLiveQuoteProvider, type LiveQuoteProvider, type LiveQuoteResult } from "../market/liveQuote.js";
import { findPair } from "../risk/pairs.js";
import { getDailyLossStatus } from "../risk/kill.js";
import { DEFAULT_RISK_LIMITS, type RiskLimits } from "../risk/limits.js";
import {
  ASSESSMENT_OUTPUT_TOKEN_LIMIT,
  ASSESSMENT_REASONING_EFFORT,
  ASSESSMENT_RESPONSE_FORMAT,
  assessmentMessages,
  parseAssessment,
} from "./assess.js";
import { buildEvidence, buildSnapshot, STRATEGY_EVIDENCE_UNAVAILABLE } from "./evidence.js";
import { DEFAULT_FRESHNESS_POLICY, type FreshnessPolicy } from "./freshness.js";
import { buildGates, researchFromEvidence } from "./gate.js";
import { loadPerformanceCatalog, rowsForSymbol, type PerformanceCatalog } from "./performance.js";
import {
  ASSESSMENT_PROMPT_VERSION,
  decisionFingerprint,
  LEVELS_UNAVAILABLE,
  LIVE_WAIT_LABEL,
  SHADOW_WAIT_LABEL,
  type DecisionRecord,
} from "./record.js";

export type DecisionRequest = {
  symbol: string;
  mode?: "historical" | "live";
  nowMs?: number;
  chatId?: number;
  root?: string;
  asOfMs?: number;
  analyze?: (symbol: string, options: AnalysisOptions) => InstrumentAnalysis;
  provider?: AiProvider | null;
  budget?: TokenBudget;
  store?: ChatStore;
  riskLimits?: RiskLimits;
  freshness?: FreshnessPolicy;
  liveQuotes?: LiveQuoteProvider;
  performanceCatalog?: PerformanceCatalog | null;
};

export async function runDecision(request: DecisionRequest): Promise<DecisionRecord> {
  const nowMs = request.nowMs ?? Date.now();
  const asOfMs = request.asOfMs ?? nowMs;
  const mode = request.mode === "live" ? "LIVE" : "HISTORICAL";
  const policy = request.freshness ?? DEFAULT_FRESHNESS_POLICY;
  const limits = request.riskLimits ?? DEFAULT_RISK_LIMITS;
  const known = findPair(request.symbol);
  const symbol = known?.symbol ?? request.symbol.trim().toUpperCase();
  let analysis: InstrumentAnalysis;
  try {
    const analyze = request.analyze ?? analyzeInstrument;
    analysis = analyze(symbol, {
      asOfMs,
      ...(request.root !== undefined ? { root: request.root } : {}),
    });
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "Market data could not be read.";
    analysis = { kind: "unavailable", symbol, text: message };
  }
  const snapshot = buildSnapshot(analysis, nowMs, mode, policy);
  const evidence = buildEvidence(analysis, nowMs);
  const quote = await (request.liveQuotes ?? createLiveQuoteProvider()).quote(symbol, nowMs);
  const liveQuote = quoteState(quote);
  if (liveQuote.status === "accepted") {
    const spreadItem = evidence.find((item) => item.id === "SPREAD_UNAVAILABLE");
    if (spreadItem !== undefined) {
      spreadItem.id = "SPREAD_FROM_VERIFIED_QUOTE";
      spreadItem.text = `Spread is ${liveQuote.spreadPips ?? "unavailable"} pips from the verified bid and ask.`;
    }
  }
  const strategyEvidence = strategyState(symbol, request.performanceCatalog);
  const strategyItem = evidence.find((item) => item.id === "STRATEGY_PERFORMANCE_UNAVAILABLE");
  if (strategyEvidence.available && strategyItem !== undefined) {
    strategyItem.id = "STRATEGY_PERFORMANCE_HISTORICAL";
    strategyItem.text = strategyEvidence.disclaimer;
  }
  const risk = riskState(request.store, request.chatId, limits, nowMs);
  const ai = await assess(request.provider ?? null, request.budget, request.chatId, symbol, evidence);
  const research = researchFromEvidence({
    snapshot,
    evidence,
    ai,
    killSwitch: risk.killSwitch === true,
  });
  const gates = buildGates({
    symbolSupported: known !== undefined && analysis.kind === "report",
    snapshot,
    evidence,
    riskPassed: false,
    riskDetail: risk.detail,
    aiStatus: ai.status === "accepted" ? "accepted" : ai.status === "rejected" ? "rejected" : "other",
    aiDetail: ai.status === "accepted" ? "AI assessment matched the schema and the supplied evidence ids." : ai.reason,
    verifiedSpread: liveQuote.status === "accepted",
  });
  const finalLabel = liveQuote.status === "accepted" ? SHADOW_WAIT_LABEL : LIVE_WAIT_LABEL;
  const waitReasons = [
    finalLabel,
    ...gates.filter((gate) => !gate.passed).map((gate) => `${gate.id}: ${gate.detail}`),
  ];
  const record: DecisionRecord = {
    id: `dec_${nowMs.toString(16)}_${randomUUID()}`,
    schemaVersion: 1,
    operatingMode: "shadow",
    symbol,
    createdAtMs: nowMs,
    chatId: request.chatId ?? null,
    snapshot,
    evidence,
    strategyEvidence,
    liveQuote,
    quotePlan: {
      entry: "NOT AVAILABLE",
      stopLoss: "NOT AVAILABLE",
      takeProfit: "NOT AVAILABLE",
      riskReward: "NOT AVAILABLE",
      positionSize: "NOT AVAILABLE",
      reason: liveQuote.status === "accepted"
        ? "A verified quote supplies bid, ask, and spread only. Account equity is not verified, and this phase has no approved stop formula. Levels stay unavailable. Shadow mode does not send an order."
        : "No verified bid or ask is available, so entry, stop, target, and position size stay unavailable.",
    },
    gates,
    risk,
    levels: {
      entry: "NOT AVAILABLE",
      stopLoss: "NOT AVAILABLE",
      takeProfit: "NOT AVAILABLE",
      positionSize: "NOT AVAILABLE",
      reason: liveQuote.status === "accepted"
        ? "NOT AVAILABLE. Bid and ask were verified, but account equity is not verified and this phase has no approved stop formula. A historical candle close is not an executable price. Shadow mode does not send an order."
        : LEVELS_UNAVAILABLE,
    },
    ai,
    research,
    finalClassification: "WAIT",
    finalLabel,
    actionable: false,
    waitReasons,
    fingerprint: decisionFingerprint({
      symbol,
      mode: snapshot.mode,
      timeframes: snapshot.timeframes,
      evidenceIds: evidence.map((item) => item.id),
    }),
    provider: request.provider ? { name: request.provider.name, model: request.provider.model } : null,
    promptVersion: ASSESSMENT_PROMPT_VERSION,
  };
  request.store?.insertDecision(record);
  return record;
}

function quoteState(result: LiveQuoteResult): DecisionRecord["liveQuote"] {
  if (!result.ok) {
    return {
      status: result.code === "unavailable" ? "unavailable" : "rejected",
      code: result.code,
      detail: result.reason,
      quotedAtMs: null,
      spreadPips: null,
      source: "none",
    };
  }
  return {
    status: "accepted",
    code: "accepted",
    detail: "Bid and ask were accepted. The quote is not an order.",
    quotedAtMs: result.quote.quotedAtMs,
    spreadPips: result.quote.spreadPips,
    source: result.quote.source,
  };
}

function strategyState(
  symbol: string,
  supplied: PerformanceCatalog | null | undefined,
): DecisionRecord["strategyEvidence"] {
  const catalog = supplied === undefined ? loadPerformanceCatalog() : supplied;
  if (catalog === null) {
    return { available: false, reason: STRATEGY_EVIDENCE_UNAVAILABLE };
  }
  const rows = rowsForSymbol(catalog, symbol);
  if (rows.length === 0) {
    return { available: false, reason: `No Phase 8 catalog row matches ${symbol}.` };
  }
  return {
    available: true,
    version: 1,
    symbol,
    disclaimer: catalog.disclaimer,
    references: rows.map((row) => ({
      strategyId: row.strategyId,
      timeframe: row.timeframe,
      startMs: row.startMs,
      endMs: row.endMs,
      sample: row.sample,
      trades: row.trades,
      netProfit: row.netProfit,
      profitFactorBeforeCosts: row.profitFactorBeforeCosts,
      maxDrawdown: row.maxDrawdown,
    })),
  };
}

function riskState(
  store: ChatStore | undefined,
  chatId: number | undefined,
  limits: RiskLimits,
  nowMs: number,
): DecisionRecord["risk"] {
  if (store === undefined || chatId === undefined) {
    return {
      status: "not_verified",
      detail: "No chat loss record was checked. Account equity, live exposure, and broker P/L are not verified.",
      userReportedLoss: null,
      killSwitch: null,
      accountEquity: "NOT VERIFIED",
      liveExposure: "NOT VERIFIED",
      brokerPnl: "NOT VERIFIED",
    };
  }
  const status = getDailyLossStatus(store, chatId, limits.maxDailyLoss, nowMs);
  if (status.killed) {
    return {
      status: "blocked",
      detail: "The user-reported daily-loss kill switch is on. Tradexa does not receive broker P/L.",
      userReportedLoss: status.totalLoss,
      killSwitch: true,
      accountEquity: "NOT VERIFIED",
      liveExposure: "NOT VERIFIED",
      brokerPnl: "NOT VERIFIED",
    };
  }
  return {
    status: "not_verified",
    detail: `User-reported loss today is ${status.totalLoss}. Account equity and live exposure are not verified, so the risk gate does not pass.`,
    userReportedLoss: status.totalLoss,
    killSwitch: false,
    accountEquity: "NOT VERIFIED",
    liveExposure: "NOT VERIFIED",
    brokerPnl: "NOT VERIFIED",
  };
}

async function assess(
  provider: AiProvider | null,
  budget: TokenBudget | undefined,
  chatId: number | undefined,
  symbol: string,
  evidence: DecisionRecord["evidence"],
): Promise<DecisionRecord["ai"]> {
  if (provider === null) {
    return { status: "not_called", reason: "AI provider is not configured. The deterministic result is unchanged." };
  }
  if (evidence.length === 0) {
    return { status: "not_called", reason: "AI was not called because there is no evidence." };
  }
  const messages = assessmentMessages({ symbol, evidence });
  const allowed = new Set(evidence.map((item) => item.id));
  try {
    const text = await requestAssessment(provider, budget, chatId, messages);
    const parsed = parseAssessment(text, allowed);
    if (!parsed.ok) {
      return { status: "rejected", reason: parsed.reason };
    }
    return { status: "accepted", assessment: parsed.assessment };
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "";
    if (message.includes("token budget")) {
      return { status: "unavailable", reason: "AI token budget is exhausted. The deterministic result is unchanged." };
    }
    const classified = classifyAiError(error);
    const label = classified.status === 429
      ? "AI rate limit."
      : classified.type === "timeout"
        ? "AI request timed out."
        : classified.status !== undefined && classified.status >= 500
          ? "AI provider returned a server error."
          : "AI provider is unavailable.";
    return { status: "unavailable", reason: `${label} The deterministic result is unchanged.` };
  }
}

async function requestAssessment(
  provider: AiProvider,
  budget: TokenBudget | undefined,
  chatId: number | undefined,
  messages: ReturnType<typeof assessmentMessages>,
): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), AI_TIMEOUT_MS);
  try {
    if (budget !== undefined && chatId !== undefined) {
      const reserved = await budget.reserve(chatId, messages);
      if (!reserved.ok) {
        return Promise.reject(new Error("AI token budget is exhausted."));
      }
      try {
        const result = await provider.chat(messages, controller.signal, {
          maxOutputTokens: reserved.maxOutputTokens,
          responseFormat: ASSESSMENT_RESPONSE_FORMAT,
          reasoningEffort: ASSESSMENT_REASONING_EFFORT,
        });
        await budget.settle(reserved.reservationId, {
          chatId,
          provider: provider.name,
          model: provider.model,
          inputTokens: result.inputTokens,
          outputTokens: result.outputTokens,
        });
        return result.text;
      } catch (error: unknown) {
        await budget.release(reserved.reservationId);
        throw error;
      }
    }
    const result = await provider.chat(messages, controller.signal, {
      maxOutputTokens: ASSESSMENT_OUTPUT_TOKEN_LIMIT,
      responseFormat: ASSESSMENT_RESPONSE_FORMAT,
      reasoningEffort: ASSESSMENT_REASONING_EFFORT,
    });
    return result.text;
  } finally {
    clearTimeout(timer);
  }
}
