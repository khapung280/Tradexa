import { readFileSync } from "node:fs";
import { resolve } from "node:path";

export const PERFORMANCE_CATALOG_VERSION = 1;
export const DEFAULT_PERFORMANCE_CATALOG = resolve("src/decision/performance/catalog.json");
export const PERFORMANCE_DISCLAIMER =
  "These figures are historical simulations from the Phase 8 engine. They are not live performance and they are not future performance.";

export type PerformanceSample = "full_sample" | "in_sample" | "out_of_sample";

export type PerformanceAssumptions = {
  initialBalance: number;
  sizing: string;
  stopLossPips: number;
  takeProfitPips: number;
  spreadPips: number;
  slippagePips: number;
  commissionPerSidePerLot: number;
};

export type PerformanceRow = {
  strategyId: string;
  strategyName: string;
  symbol: string;
  timeframe: string;
  startMs: number;
  endMs: number;
  sample: PerformanceSample;
  trades: number;
  netProfit: number;
  grossProfit: number;
  grossLoss: number;
  profitFactorBeforeCosts: number | null;
  profitFactorAfterCosts: number | null;
  executionCosts: number;
  commission: number;
  maxDrawdown: number;
  assumptions: PerformanceAssumptions;
};

export type PerformanceCatalog = {
  version: 1;
  source: "phase8-runBacktest";
  disclaimer: string;
  rows: PerformanceRow[];
};

const SAMPLES = new Set<PerformanceSample>(["full_sample", "in_sample", "out_of_sample"]);

export function parsePerformanceCatalog(
  value: unknown,
): { ok: true; catalog: PerformanceCatalog } | { ok: false; reason: string } {
  if (!isRecord(value) || value.version !== PERFORMANCE_CATALOG_VERSION || value.source !== "phase8-runBacktest") {
    return { ok: false, reason: "Performance catalog version or source is not recognized." };
  }
  if (typeof value.disclaimer !== "string" || value.disclaimer.length === 0) {
    return { ok: false, reason: "Performance catalog is missing its disclaimer." };
  }
  if (!Array.isArray(value.rows) || value.rows.some((row) => !isRow(row))) {
    return { ok: false, reason: "Performance catalog has an invalid row." };
  }
  return { ok: true, catalog: value as PerformanceCatalog };
}

export function loadPerformanceCatalog(path = DEFAULT_PERFORMANCE_CATALOG): PerformanceCatalog | null {
  let text: string;
  try {
    text = readFileSync(path, "utf8");
  } catch {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  const catalog = parsePerformanceCatalog(parsed);
  return catalog.ok ? catalog.catalog : null;
}

export function rowsForSymbol(catalog: PerformanceCatalog, symbol: string): PerformanceRow[] {
  return catalog.rows.filter((row) => row.symbol === symbol);
}

function isRow(value: unknown): value is PerformanceRow {
  if (!isRecord(value) || !isRecord(value.assumptions)) {
    return false;
  }
  return typeof value.strategyId === "string"
    && typeof value.symbol === "string"
    && typeof value.timeframe === "string"
    && SAMPLES.has(value.sample as PerformanceSample)
    && Number.isFinite(value.trades)
    && Number.isFinite(value.netProfit)
    && Number.isFinite(value.maxDrawdown)
    && (value.profitFactorBeforeCosts === null || Number.isFinite(value.profitFactorBeforeCosts))
    && (value.profitFactorAfterCosts === null || Number.isFinite(value.profitFactorAfterCosts))
    && typeof value.assumptions.sizing === "string";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
