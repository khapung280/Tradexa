import type { EvidenceItem, GateCheck, MarketSnapshot, ResearchAssessment, AiAssessmentState } from "./record.js";
import { RESEARCH_LABEL } from "./record.js";

const REQUIRED = ["H1", "H4"] as const;

export function buildGates(input: {
  symbolSupported: boolean;
  snapshot: MarketSnapshot;
  evidence: readonly EvidenceItem[];
  riskPassed: boolean;
  riskDetail: string;
  aiStatus: "accepted" | "rejected" | "other";
  aiDetail: string;
  verifiedSpread?: boolean;
}): GateCheck[] {
  const required = REQUIRED.map((timeframe) => input.snapshot.timeframes.find((item) => item.timeframe === timeframe));
  const closed = required.every((item) => item?.closed === true);
  const enough = required.every((item) => item?.completeness === "complete");
  const present = required.every((item) => item !== undefined && item.completeness !== "unavailable");
  const gaps = input.snapshot.timeframes.some((item) => item.gapQuality === "unclassified_gap");
  const fresh = input.snapshot.mode === "LIVE" && required.every((item) => item?.freshness === "fresh");
  const technical = input.evidence.some((item) => item.id.startsWith("TREND_"));
  const conflict = input.evidence.some((item) => item.id === "MTF_CONFLICT_BULLISH_BEARISH");
  const idsOk = input.aiStatus !== "rejected";
  return [
    { id: "symbol", passed: input.symbolSupported, detail: input.symbolSupported ? "Symbol is supported." : "Symbol is not supported, so no prices were estimated." },
    { id: "snapshot", passed: input.snapshot.timeframes.some((item) => item.completeness !== "unavailable"), detail: "Snapshot comes from the native candle reader." },
    { id: "data_mode", passed: input.verifiedSpread === true, detail: input.verifiedSpread === true ? "A verified bid/ask quote was accepted. Shadow mode still does not send an order." : input.snapshot.mode === "LIVE" ? "Live mode was requested, but no verified live source exists." : "Data mode is historical. No verified live quote was accepted." },
    { id: "freshness", passed: fresh, detail: fresh ? "Required candles are inside the live freshness window." : "Candles are not a fresh live quote." },
    { id: "closed_candles", passed: closed, detail: closed ? "Required H1 and H4 candles are closed." : "A required closed candle is missing." },
    { id: "history", passed: enough, detail: enough ? "H1 and H4 each have at least 200 closed candles." : "H1 or H4 does not have enough closed history." },
    { id: "completeness", passed: present && enough, detail: "Completeness uses the closed candles after the latest unclassified gap." },
    { id: "gaps", passed: !gaps, detail: gaps ? "An unclassified gap is present. It is not treated as a verified market closure." : "No unclassified gap was observed in the loaded windows." },
    { id: "technical_evidence", passed: technical, detail: technical ? "Trend classes came from the Phase 7 engine." : "Phase 7 did not produce a trend class." },
    { id: "timeframe_conflict", passed: !conflict, detail: conflict ? "At least two timeframes disagree. The conflict is kept." : "No bullish-versus-bearish trend conflict was found." },
    { id: "execution_assumptions", passed: input.verifiedSpread === true, detail: input.verifiedSpread === true ? "Spread was calculated from a verified bid and ask." : input.snapshot.spread.reason },
    { id: "risk", passed: input.riskPassed, detail: input.riskDetail },
    { id: "ai_response", passed: input.aiStatus === "accepted", detail: input.aiDetail },
    { id: "evidence_ids", passed: idsOk, detail: idsOk ? "Cited evidence ids are limited to the supplied set." : "The AI response failed evidence-id validation." },
    { id: "operating_mode", passed: false, detail: "Shadow mode does not publish an actionable BUY or SELL and does not send an order." },
  ];
}

export function researchFromEvidence(input: {
  snapshot: MarketSnapshot;
  evidence: readonly EvidenceItem[];
  ai: AiAssessmentState;
  killSwitch: boolean;
}): ResearchAssessment {
  const bullish = input.evidence.filter((item) => item.polarity === "bullish").map((item) => item.text);
  const bearish = input.evidence.filter((item) => item.polarity === "bearish").map((item) => item.text);
  const conflicts = input.evidence.filter((item) => item.id === "MTF_CONFLICT_BULLISH_BEARISH" || item.id.startsWith("DATA_")).map((item) => item.text);
  const base: Omit<ResearchAssessment, "classification" | "reason"> = {
    applicability: "NON_ACTIONABLE",
    label: RESEARCH_LABEL,
    bullish,
    bearish,
    conflicts,
  };
  if (input.killSwitch) {
    return { ...base, classification: "WAIT", reason: "WAIT: the user-reported daily-loss kill switch is on. This is not broker P/L." };
  }
  const h1 = input.snapshot.timeframes.find((item) => item.timeframe === "H1");
  const h4 = input.snapshot.timeframes.find((item) => item.timeframe === "H4");
  if (h1?.completeness !== "complete" || h4?.completeness !== "complete") {
    return { ...base, classification: "WAIT", reason: "WAIT: H1 and H4 do not both have 200 closed candles." };
  }
  const h1Trend = trendDirection(input.evidence, "H1");
  const h4Trend = trendDirection(input.evidence, "H4");
  const bearishEvidence = input.evidence.some((item) => item.polarity === "bearish");
  const bullishEvidence = input.evidence.some((item) => item.polarity === "bullish");
  let classification: ResearchAssessment["classification"] = "WAIT";
  let reason = "WAIT: H1 and H4 are not aligned.";
  if (h1Trend === "bullish" && h4Trend === "bullish" && !bearishEvidence) {
    classification = "BUY";
    reason = "H1 and H4 trend classes are bullish and no calculated evidence is bearish. This is historical research, not a live signal.";
  } else if (h1Trend === "bearish" && h4Trend === "bearish" && !bullishEvidence) {
    classification = "SELL";
    reason = "H1 and H4 trend classes are bearish and no calculated evidence is bullish. This is historical research, not a live signal.";
  } else if (bullishEvidence && bearishEvidence) {
    reason = "WAIT: bullish and bearish evidence are both present.";
  }
  if (input.ai.status === "accepted") {
    const recommended = input.ai.assessment.recommendedClassification;
    if (classification !== "WAIT" && recommended !== classification) {
      return {
        ...base,
        classification: "WAIT",
        reason: "WAIT: the AI assessment did not confirm the deterministic direction. The AI result cannot upgrade or replace the gate.",
      };
    }
    if (classification === "WAIT" && recommended !== "WAIT") {
      return {
        ...base,
        classification: "WAIT",
        reason: "WAIT: the deterministic evidence does not support a direction. The AI recommendation was not used.",
      };
    }
  }
  return { ...base, classification, reason };
}

function trendDirection(evidence: readonly EvidenceItem[], timeframe: string): "bullish" | "bearish" | "other" {
  if (evidence.some((item) => item.id === `TREND_${timeframe}_BULLISH`)) {
    return "bullish";
  }
  if (evidence.some((item) => item.id === `TREND_${timeframe}_BEARISH`)) {
    return "bearish";
  }
  return "other";
}
