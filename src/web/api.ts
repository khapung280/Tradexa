import { createHash, timingSafeEqual } from "node:crypto";
import { analyzeTimeframe } from "../analysis/technical.js";
import { ema, macd, rsi } from "../analysis/indicators.js";
import type { AiProvider } from "../ai/types.js";
import type { TokenBudget } from "../budget/ledger.js";
import { readAiConfig } from "../config/env.js";
import type { ChatStore } from "../db/store.js";
import { loadPerformanceCatalog } from "../decision/performance.js";
import type { DecisionRecord } from "../decision/record.js";
import { runDecision } from "../decision/run.js";
import { createLiveQuoteProvider } from "../market/liveQuote.js";
import {
  eurusdDatasetRoot,
  isNativeAnalysisTimeframe,
  readClosedNativeCandles,
  type NativeAnalysisTimeframe,
} from "../market/nativeCandles.js";
import { getDailyLossStatus } from "../risk/kill.js";
import { DEFAULT_RISK_LIMITS, type RiskLimits } from "../risk/limits.js";

export type DashboardRequest = {
  method: string;
  path: string;
  search: URLSearchParams;
  peer: string;
  authorization: string | null;
  host?: string;
  origin?: string | null;
  nowMs: number;
  token: string | null;
  store?: ChatStore;
  provider?: AiProvider | null;
  budget?: TokenBudget;
  riskLimits?: RiskLimits;
};

export type DashboardResponse = {
  status: number;
  contentType: string;
  body: string;
};

const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);

export async function handleDashboardRequest(request: DashboardRequest): Promise<DashboardResponse> {
  if (!LOOPBACK.has(request.peer)) {
    return json(403, { error: "The dashboard accepts connections from this machine only." });
  }
  if (request.host !== undefined && !localHostHeader(request.host)) {
    return json(403, { error: "The dashboard accepts connections from this machine only." });
  }
  if (request.origin != null && request.origin.length > 0 && !localOrigin(request.origin)) {
    return json(403, { error: "The dashboard accepts connections from this machine only." });
  }
  const symbol = request.search.get("symbol") ?? "EURUSD";
  if (request.path.startsWith("/api/") && symbol !== "EURUSD") {
    return json(400, { error: "EURUSD is the only symbol with native candles. No prices were estimated." });
  }
  try {
    if (request.method === "GET" && request.path === "/api/overview") {
      return json(200, await overview(request, authorized(request.token, request.authorization)));
    }
    if (request.method === "GET" && request.path === "/api/settings") {
      return json(200, await settings(request.nowMs));
    }
    if (request.method === "GET" && request.path === "/api/performance") {
      return json(200, performance());
    }
    if (request.method === "GET" && request.path === "/api/decisions") {
      if (!authorized(request.token, request.authorization)) {
        return json(403, { error: "Decision history requires the local DASHBOARD_TOKEN. No order was sent." });
      }
      return json(200, { decisions: (request.store?.listRecentDecisions(10) ?? []).map(publicDecision) });
    }
    if (request.method === "GET" && request.path === "/api/risk") {
      if (request.search.get("chatId") !== null && !authorized(request.token, request.authorization)) {
        return json(403, { error: "User risk status requires the local DASHBOARD_TOKEN. No order was sent." });
      }
      return json(200, risk(request));
    }
    if (request.method === "GET" && request.path === "/api/analysis") {
      return json(200, analysis(request.search.get("timeframe") ?? "H1", request.nowMs));
    }
    if (request.method === "GET" && request.path === "/api/candles") {
      return json(200, candles(request.search.get("timeframe") ?? "H1", request.search.get("limit"), request.nowMs));
    }
    if (request.method === "POST" && request.path === "/api/decision") {
      if (!authorized(request.token, request.authorization)) {
        return json(403, { error: "A dashboard assessment requires the local DASHBOARD_TOKEN. No order was sent." });
      }
      const record = await runDecision({
        symbol: "EURUSD",
        nowMs: request.nowMs,
        ...(request.store !== undefined ? { store: request.store } : {}),
        ...(request.provider !== undefined ? { provider: request.provider } : {}),
        ...(request.budget !== undefined ? { budget: request.budget } : {}),
        ...(request.riskLimits !== undefined ? { riskLimits: request.riskLimits } : {}),
      });
      return json(200, { decision: publicDecision(record) });
    }
    return json(404, { error: "That dashboard route does not exist." });
  } catch (error: unknown) {
    return json(400, { error: publicError(error) });
  }
}

async function overview(request: DashboardRequest, includeDecision: boolean) {
  const provider = createLiveQuoteProvider();
  const quote = await provider.quote("EURUSD", request.nowMs);
  const latest = includeDecision ? request.store?.listRecentDecisions(1)[0] : undefined;
  const catalog = loadPerformanceCatalog();
  const latestCandle = latestClosedCandle(request.nowMs);
  return {
    platform: "shadow",
    ordersEnabled: false,
    symbol: "EURUSD",
    dataMode: "HISTORICAL",
    dataSource: latestCandle.source,
    lastCandleTimeMs: latestCandle.timeMs,
    liveQuote: publicQuote(provider.configured, provider.name, quote),
    performanceRows: catalog?.rows.length ?? 0,
    decisionAccess: includeDecision ? "authorized" : "locked",
    latestDecision: latest === undefined ? null : publicDecision(latest),
  };
}

function publicQuote(configured: boolean, name: string, quote: Awaited<ReturnType<ReturnType<typeof createLiveQuoteProvider>["quote"]>>) {
  if (!quote.ok) {
    return {
      configured,
      name,
      status: quote.code,
      bid: null,
      ask: null,
      spreadPips: null,
      quotedAtMs: null,
    };
  }
  return {
    configured,
    name,
    status: "accepted",
    bid: quote.quote.bid,
    ask: quote.quote.ask,
    spreadPips: quote.quote.spreadPips,
    quotedAtMs: quote.quote.quotedAtMs,
  };
}

async function settings(nowMs: number) {
  const ai = configuredAi();
  const quotes = createLiveQuoteProvider();
  const quote = await quotes.quote("EURUSD", nowMs);
  return {
    symbol: "EURUSD",
    timeframes: ["M5", "M15", "M30", "H1", "H4", "D1"],
    dataMode: "HISTORICAL",
    liveQuoteConfigured: quotes.configured,
    liveQuoteStatus: quote.ok ? "accepted" : quote.code,
    aiConfigured: ai.configured,
    aiModel: ai.model,
    dashboardPostsRequireToken: true,
    tokenStorage: "session",
    secretsIncluded: false,
  };
}

function configuredAi(): { configured: boolean; model: string | null } {
  try {
    const ai = readAiConfig(process.env);
    return { configured: ai !== null, model: ai?.model ?? null };
  } catch {
    return { configured: false, model: null };
  }
}

function performance() {
  const catalog = loadPerformanceCatalog();
  if (catalog === null) {
    return { available: false, disclaimer: "No verified Phase 8 catalog is available.", rows: [] };
  }
  return { available: true, disclaimer: catalog.disclaimer, rows: catalog.rows };
}

function risk(request: DashboardRequest) {
  const chatRaw = request.search.get("chatId");
  const limits = request.riskLimits ?? DEFAULT_RISK_LIMITS;
  const base = {
    maxRiskPercent: limits.maxRiskPercent,
    maxLots: limits.maxLots,
    maxDailyLoss: limits.maxDailyLoss,
    brokerPnl: "NOT VERIFIED",
    accountEquity: "NOT VERIFIED",
    source: "User-reported losses are not broker P/L.",
    killSwitch: "The kill switch tracks user-reported losses for one chat. It is not a broker equity stop.",
  };
  if (chatRaw === null || request.store === undefined) {
    return { ...base, chat: null };
  }
  if (!/^\d+$/.test(chatRaw)) {
    throw new Error("chatId must be a positive integer.");
  }
  const status = getDailyLossStatus(request.store, Number(chatRaw), limits.maxDailyLoss, request.nowMs);
  return {
    ...base,
    chat: {
      totalLoss: status.totalLoss,
      killed: status.killed,
      label: "user-reported",
    },
  };
}

function analysis(timeframeRaw: string, nowMs: number) {
  if (!isNativeAnalysisTimeframe(timeframeRaw)) {
    throw new Error("Timeframe must be M5, M15, M30, H1, H4, or D1.");
  }
  const section = analyzeTimeframe(timeframeRaw, {
    root: eurusdDatasetRoot(),
    asOfMs: nowMs,
    lookbackBars: 400,
    cache: null,
  });
  if (section.status !== "ok") {
    return { timeframe: timeframeRaw, dataMode: "HISTORICAL", available: false, reason: section.reason };
  }
  return {
    timeframe: timeframeRaw,
    dataMode: "HISTORICAL",
    available: true,
    candleOpenMs: section.candleOpenMs,
    candleCloseMs: section.candleCloseMs,
    close: section.close,
    trend: section.trend.classification,
    indicators: section.indicators,
    structure: section.structure.state,
    warnings: section.warnings,
    support: section.zones.support.map(zone),
    resistance: section.zones.resistance.map(zone),
  };
}

function candles(timeframeRaw: string, limitRaw: string | null, nowMs: number) {
  if (!isNativeAnalysisTimeframe(timeframeRaw)) {
    throw new Error("Timeframe must be M5, M15, M30, H1, H4, or D1.");
  }
  const limit = limitRaw === null ? 120 : Number(limitRaw);
  if (!Number.isInteger(limit) || limit < 20 || limit > 400) {
    throw new Error("Candle limit must be an integer from 20 to 400.");
  }
  const read = readClosedNativeCandles(timeframeRaw as NativeAnalysisTimeframe, {
    root: eurusdDatasetRoot(),
    lookbackBars: limit,
    asOfMs: nowMs,
    cache: null,
  });
  if (read.status !== "ok") {
    return { available: false, dataMode: "HISTORICAL", reason: read.reason, candles: [] };
  }
  const bars = read.candles.slice(-limit);
  const closes = bars.map((bar) => bar.close);
  const ema20 = ema(closes, 20);
  const ema50 = ema(closes, 50);
  const ema200 = ema(closes, 200);
  const rsi14 = rsi(closes, 14);
  const macdPoints = macd(closes, 12, 26, 9);
  return {
    available: true,
    dataMode: "HISTORICAL",
    source: "Dukascopy native bid CSV. Not a live feed.",
    timeframe: timeframeRaw,
    candles: bars.map((bar, index) => ({
      timeMs: bar.timeMs,
      open: bar.open,
      high: bar.high,
      low: bar.low,
      close: bar.close,
      ema20: ema20[index],
      ema50: ema50[index],
      ema200: ema200[index],
      rsi14: rsi14[index],
      macd: macdPoints[index]?.macd ?? null,
      signal: macdPoints[index]?.signal ?? null,
    })),
  };
}

function zone(item: { lower: number; upper: number; strength: string }) {
  return { lower: item.lower, upper: item.upper, strength: item.strength };
}

function publicDecision(record: DecisionRecord) {
  const ids = (polarity: string) => record.evidence.filter((item) => item.polarity === polarity).map((item) => item.id);
  return {
    id: record.id,
    symbol: record.symbol,
    createdAtMs: record.createdAtMs,
    tradingClassification: record.finalClassification,
    tradingLabel: record.finalLabel,
    researchClassification: record.research.classification,
    researchLabel: record.research.label,
    researchReason: record.research.reason,
    actionable: record.actionable,
    waitReasons: record.waitReasons,
    liveQuote: record.liveQuote.status,
    levels: record.quotePlan.reason,
    risk: record.risk.status,
    riskDetail: record.risk.detail,
    killSwitch: record.risk.killSwitch,
    ai: record.ai.status,
    aiDetail: record.ai.status === "accepted" ? record.ai.assessment.assessmentSummary : record.ai.reason,
    evidenceIds: record.evidence.map((item) => item.id),
    bullish: ids("bullish"),
    bearish: ids("bearish"),
    warnings: ids("warning"),
  };
}

function authorized(token: string | null, header: string | null): boolean {
  if (token === null || token.length === 0 || header === null || !header.startsWith("Bearer ")) {
    return false;
  }
  const presented = header.slice("Bearer ".length);
  if (presented.length === 0) {
    return false;
  }
  const actual = createHash("sha256").update(presented).digest();
  const expected = createHash("sha256").update(token).digest();
  return timingSafeEqual(actual, expected);
}

function localHostHeader(host: string): boolean {
  const value = host.trim().toLowerCase();
  if (value.length === 0) {
    return false;
  }
  const hostname = value.startsWith("[")
    ? value.slice(1, value.indexOf("]"))
    : value.replace(/:\d+$/, "");
  return hostname === "127.0.0.1" || hostname === "localhost" || hostname === "::1";
}

function localOrigin(origin: string): boolean {
  try {
    const url = new URL(origin);
    return url.protocol === "http:" && (url.hostname === "127.0.0.1" || url.hostname === "localhost" || url.hostname === "::1");
  } catch {
    return false;
  }
}

function latestClosedCandle(nowMs: number): { source: string; timeMs: number | null } {
  try {
    const body = candles("H1", "20", nowMs);
    const source = "source" in body && typeof body.source === "string"
      ? body.source
      : "Dukascopy native bid CSV. Not a live feed.";
    const last = body.candles[body.candles.length - 1];
    return { source, timeMs: last === undefined ? null : last.timeMs };
  } catch {
    return { source: "Dukascopy native bid CSV. Not a live feed.", timeMs: null };
  }
}

const SAFE_ERRORS = [
  "Timeframe must be M5, M15, M30, H1, H4, or D1.",
  "Candle limit must be an integer from 20 to 400.",
  "chatId must be a positive integer.",
];

function publicError(error: unknown): string {
  const message = error instanceof Error ? error.message : "";
  if (SAFE_ERRORS.includes(message)) {
    return message;
  }
  return "The dashboard request failed. No order was sent.";
}

function json(status: number, body: unknown): DashboardResponse {
  return { status, contentType: "application/json; charset=utf-8", body: JSON.stringify(body) };
}
