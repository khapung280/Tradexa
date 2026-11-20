import type { AiProvider } from "../ai/types.js";
import type { InstrumentAnalysis } from "./technical.js";

export const EXPLANATION_CATEGORIES = [
  "aligned_bullish",
  "aligned_bearish",
  "aligned_sideways",
  "timeframe_conflict",
  "mixed_or_incomplete",
] as const;
export type ExplanationCategory = (typeof EXPLANATION_CATEGORIES)[number];

export type ExplanationEvidence = {
  allowedCategories: readonly ExplanationCategory[];
  items: readonly { id: string; text: string }[];
};

const TEMPLATES: Record<ExplanationCategory, string> = {
  aligned_bullish:
    "The calculated trend classes agree and are bullish. This describes completed candles and is not a prediction.",
  aligned_bearish:
    "The calculated trend classes agree and are bearish. This describes completed candles and is not a prediction.",
  aligned_sideways:
    "The calculated trend classes agree and are sideways. This describes completed candles and is not a prediction.",
  timeframe_conflict:
    "The calculated trend classes conflict across timeframes. This describes completed candles and is not a prediction.",
  mixed_or_incomplete:
    "The calculated trend classes do not share one direction, or some timeframes do not have enough history. This is not a prediction.",
};

export const ANALYSIS_EXPLANATION_PROMPT = [
  "You select evidence for a technical report that Tradexa already calculated.",
  "Reply with one JSON object and no other text.",
  'The object must be {"evidenceIds":["id"],"category":"category"}.',
  "Use only evidence ids and categories from the user message.",
  "Do not add prices, indicator values, timestamps, pairs, or a trend that is not one of the allowed categories.",
].join("\n");

export function explanationEvidence(analysis: InstrumentAnalysis): ExplanationEvidence {
  if (analysis.kind !== "report") {
    return { allowedCategories: ["mixed_or_incomplete"], items: [] };
  }
  const classes = analysis.sections.flatMap((section) => {
    if (section.status !== "ok" || section.trend.classification === "insufficient_data") {
      return [];
    }
    return [section.trend.classification];
  });
  const allowed = new Set<ExplanationCategory>();
  if (classes.length > 0 && classes.every((item) => item === "bullish")) {
    allowed.add("aligned_bullish");
  }
  if (classes.length > 0 && classes.every((item) => item === "bearish")) {
    allowed.add("aligned_bearish");
  }
  if (classes.length > 0 && classes.every((item) => item === "sideways")) {
    allowed.add("aligned_sideways");
  }
  if (classes.includes("bullish") && classes.includes("bearish")) {
    allowed.add("timeframe_conflict");
  }
  if (allowed.size === 0) {
    allowed.add("mixed_or_incomplete");
  }

  const items: { id: string; text: string }[] = [];
  for (const section of analysis.sections) {
    if (section.status !== "ok" || section.trend.classification === "insufficient_data") {
      continue;
    }
    items.push({
      id: `trend-${section.timeframe}`,
      text: `${section.timeframe} trend class is ${section.trend.classification}.`,
    });
  }
  if (allowed.has("aligned_bullish") || allowed.has("aligned_bearish") || allowed.has("aligned_sideways")) {
    items.push({ id: "agreement", text: "The available trend classes agree." });
  }
  if (allowed.has("timeframe_conflict")) {
    items.push({ id: "conflict", text: "At least one timeframe is bullish and another is bearish." });
  }
  return { allowedCategories: [...allowed], items };
}

export function explanationMessages(evidence: ExplanationEvidence): {
  role: "system" | "user";
  content: string;
}[] {
  return [
    { role: "system", content: ANALYSIS_EXPLANATION_PROMPT },
    {
      role: "user",
      content: JSON.stringify({
        allowedCategories: evidence.allowedCategories,
        evidenceIds: evidence.items.map((item) => item.id),
      }),
    },
  ];
}

export function acceptExplanation(
  raw: string,
  evidence: ExplanationEvidence,
): { ok: true; text: string } | { ok: false; reason: string } {
  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    return { ok: false, reason: "empty" };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { ok: false, reason: "invalid" };
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return { ok: false, reason: "invalid" };
  }
  const record = parsed as { evidenceIds?: unknown; category?: unknown };
  const keys = Object.keys(record);
  if (keys.some((key) => key !== "evidenceIds" && key !== "category")) {
    return { ok: false, reason: "invalid" };
  }
  if (!Array.isArray(record.evidenceIds) || typeof record.category !== "string") {
    return { ok: false, reason: "invalid" };
  }
  if (!evidence.allowedCategories.includes(record.category as ExplanationCategory)) {
    return { ok: false, reason: "category" };
  }
  const category = record.category as ExplanationCategory;
  const known = new Map(evidence.items.map((item) => [item.id, item.text]));
  const selected: string[] = [];
  for (const id of record.evidenceIds) {
    if (typeof id !== "string" || !known.has(id)) {
      return { ok: false, reason: "evidence" };
    }
    const text = known.get(id);
    if (text !== undefined) {
      selected.push(text);
    }
  }
  if (selected.length === 0) {
    return { ok: false, reason: "evidence" };
  }
  return { ok: true, text: [TEMPLATES[category], ...selected].join("\n") };
}

export async function explainTechnicalReport(
  provider: AiProvider,
  evidence: ExplanationEvidence,
  maxOutputTokens: number,
): Promise<{
  accepted: ReturnType<typeof acceptExplanation>;
  inputTokens: number;
  outputTokens: number;
}> {
  const result = await provider.chat(explanationMessages(evidence), AbortSignal.timeout(20_000), {
    maxOutputTokens,
  });
  return {
    accepted: acceptExplanation(result.text, evidence),
    inputTokens: result.inputTokens,
    outputTokens: result.outputTokens,
  };
}
