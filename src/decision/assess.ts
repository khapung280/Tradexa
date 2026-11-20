import type { AiMessage, JsonSchemaResponseFormat } from "../ai/types.js";
import { ASSESSMENT_SCHEMA_VERSION, type AiAssessment, type Classification, type EvidenceItem, type MarketBias } from "./record.js";

const LIST_ITEM_MAX = 240;
const SUMMARY_MAX = 500;
const REASON_LIST_MAX = 8;
const EVIDENCE_ID_MAX = 12;

export const ASSESSMENT_PROMPT = [
  "You assess evidence that Tradexa already calculated.",
  "Return one raw JSON object and no other characters.",
  "Use exactly these ten keys and no others: schemaVersion, marketBias, evidenceIds, supportingReasons, contradictingReasons, missingEvidence, riskObservations, assessmentSummary, recommendedClassification, limitations.",
  "schemaVersion is the integer 1.",
  "marketBias is BULLISH, BEARISH, or NEUTRAL.",
  "recommendedClassification is BUY, SELL, or WAIT. It is research commentary and does not authorize a trade.",
  `evidenceIds is an array of at most ${EVIDENCE_ID_MAX} supplied ids. Each item is a non-empty string of at most ${LIST_ITEM_MAX} characters. Use [] when none apply. Do not invent ids or an empty string.`,
  `supportingReasons, contradictingReasons, missingEvidence, riskObservations, and limitations are arrays of at most ${REASON_LIST_MAX} items. Each item is a non-empty string of at most ${LIST_ITEM_MAX} characters. Use [] when you have no items. Do not use an empty string.`,
  `assessmentSummary is one non-empty string of at most ${SUMMARY_MAX} characters.`,
  "Do not use markdown fences or prose outside the object.",
  "Do not invent a price, indicator, entry, stop, target, spread, lot size, account balance, win rate, or profit figure.",
  "A historical candle is not a live quote. Qualitative wording is not a probability of winning.",
].join(" ");

const REQUIRED_KEYS = [
  "schemaVersion",
  "marketBias",
  "evidenceIds",
  "supportingReasons",
  "contradictingReasons",
  "missingEvidence",
  "riskObservations",
  "assessmentSummary",
  "recommendedClassification",
  "limitations",
] as const;

export const ASSESSMENT_OUTPUT_TOKEN_LIMIT = 2_048;
export const ASSESSMENT_REASONING_EFFORT = "low" as const;

function listSchema(maxItems: number): { type: "array"; maxItems: number; items: { type: "string" } } {
  return { type: "array", maxItems, items: { type: "string" } };
}

export const ASSESSMENT_RESPONSE_FORMAT: JsonSchemaResponseFormat = {
  type: "json_schema",
  json_schema: {
    name: "tradexa_assessment",
    strict: true,
    schema: {
      type: "object",
      additionalProperties: false,
      properties: {
        schemaVersion: { type: "integer", minimum: 1, maximum: 1 },
        marketBias: { type: "string", enum: ["BULLISH", "BEARISH", "NEUTRAL"] },
        evidenceIds: listSchema(EVIDENCE_ID_MAX),
        supportingReasons: listSchema(REASON_LIST_MAX),
        contradictingReasons: listSchema(REASON_LIST_MAX),
        missingEvidence: listSchema(REASON_LIST_MAX),
        riskObservations: listSchema(REASON_LIST_MAX),
        assessmentSummary: { type: "string" },
        recommendedClassification: { type: "string", enum: ["BUY", "SELL", "WAIT"] },
        limitations: listSchema(REASON_LIST_MAX),
      },
      required: [...REQUIRED_KEYS],
    },
  },
};

const BIASES = new Set<MarketBias>(["BULLISH", "BEARISH", "NEUTRAL"]);
const CLASSES = new Set<Classification>(["BUY", "SELL", "WAIT"]);

export function assessmentMessages(input: {
  symbol: string;
  evidence: readonly EvidenceItem[];
}): AiMessage[] {
  return [
    { role: "system", content: ASSESSMENT_PROMPT },
    {
      role: "user",
      content: JSON.stringify({
        symbol: input.symbol,
        evidence: input.evidence.map((item) => ({ id: item.id, polarity: item.polarity, text: item.text })),
      }),
    },
  ];
}

export function parseAssessment(
  raw: string,
  allowedIds: ReadonlySet<string>,
): { ok: true; assessment: AiAssessment } | { ok: false; reason: string } {
  const trimmed = raw.trim();
  if (trimmed.length === 0) {
    return { ok: false, reason: "AI response was empty." };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { ok: false, reason: "AI response was not valid JSON." };
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return { ok: false, reason: "AI response was not a JSON object." };
  }
  const record = parsed as Record<string, unknown>;
  const keys = Object.keys(record);
  const missingKey = REQUIRED_KEYS.find((key) => !keys.includes(key));
  if (missingKey !== undefined) {
    return { ok: false, reason: `AI assessment field ${missingKey} was missing.` };
  }
  const unexpectedKey = keys.find((key) => !REQUIRED_KEYS.includes(key as (typeof REQUIRED_KEYS)[number]));
  if (unexpectedKey !== undefined) {
    return { ok: false, reason: `AI assessment field ${unexpectedKey} was unexpected.` };
  }
  if (record.schemaVersion !== ASSESSMENT_SCHEMA_VERSION) {
    return { ok: false, reason: "AI assessment schema version was not 1." };
  }
  if (!BIASES.has(record.marketBias as MarketBias)) {
    return { ok: false, reason: "AI market bias was not an allowed value." };
  }
  if (!CLASSES.has(record.recommendedClassification as Classification)) {
    return { ok: false, reason: "AI classification was not BUY, SELL, or WAIT." };
  }
  const evidenceIds = stringList(record.evidenceIds, "evidenceIds", EVIDENCE_ID_MAX);
  if (!evidenceIds.ok) return evidenceIds;
  const supportingReasons = stringList(record.supportingReasons, "supportingReasons", REASON_LIST_MAX);
  if (!supportingReasons.ok) return supportingReasons;
  const contradictingReasons = stringList(record.contradictingReasons, "contradictingReasons", REASON_LIST_MAX);
  if (!contradictingReasons.ok) return contradictingReasons;
  const missingEvidence = stringList(record.missingEvidence, "missingEvidence", REASON_LIST_MAX);
  if (!missingEvidence.ok) return missingEvidence;
  const riskObservations = stringList(record.riskObservations, "riskObservations", REASON_LIST_MAX);
  if (!riskObservations.ok) return riskObservations;
  const limitations = stringList(record.limitations, "limitations", REASON_LIST_MAX);
  if (!limitations.ok) return limitations;
  if (typeof record.assessmentSummary !== "string") {
    return { ok: false, reason: "AI assessment field assessmentSummary was not a string." };
  }
  if (record.assessmentSummary.length === 0) {
    return { ok: false, reason: "AI assessment field assessmentSummary was empty." };
  }
  if (record.assessmentSummary.length > SUMMARY_MAX) {
    return { ok: false, reason: `AI assessment field assessmentSummary was ${record.assessmentSummary.length} characters, maximum ${SUMMARY_MAX}.` };
  }
  if (evidenceIds.values.some((id) => !allowedIds.has(id))) {
    return { ok: false, reason: "AI response cited an evidence id that was not supplied." };
  }
  const prose = [
    record.assessmentSummary,
    ...supportingReasons.values,
    ...contradictingReasons.values,
    ...missingEvidence.values,
    ...riskObservations.values,
    ...limitations.values,
  ].join(" ");
  if (/\b\d+\.\d{3,}\b/.test(prose) || /win rate|account balance|lot size|entry price|stop loss|take profit/i.test(prose)) {
    return { ok: false, reason: "AI response included an unsupported price or performance claim." };
  }
  return {
    ok: true,
    assessment: {
      schemaVersion: 1,
      marketBias: record.marketBias as MarketBias,
      evidenceIds: evidenceIds.values,
      supportingReasons: supportingReasons.values,
      contradictingReasons: contradictingReasons.values,
      missingEvidence: missingEvidence.values,
      riskObservations: riskObservations.values,
      assessmentSummary: record.assessmentSummary,
      recommendedClassification: record.recommendedClassification as Classification,
      limitations: limitations.values,
    },
  };
}

function stringList(
  value: unknown,
  field: string,
  maxItems: number,
): { ok: true; values: string[] } | { ok: false; reason: string } {
  if (!Array.isArray(value)) {
    return { ok: false, reason: `AI assessment field ${field} was not an array.` };
  }
  if (value.length > maxItems) {
    return { ok: false, reason: `AI assessment field ${field} had ${value.length} items, maximum ${maxItems}.` };
  }
  for (let index = 0; index < value.length; index += 1) {
    const item = value[index];
    if (typeof item !== "string") {
      return { ok: false, reason: `AI assessment field ${field} item ${index} was not a string.` };
    }
    if (item.length === 0) {
      return { ok: false, reason: `AI assessment field ${field} item ${index} was empty.` };
    }
    if (item.length > LIST_ITEM_MAX) {
      return { ok: false, reason: `AI assessment field ${field} item ${index} was ${item.length} characters, maximum ${LIST_ITEM_MAX}.` };
    }
  }
  return { ok: true, values: value as string[] };
}
