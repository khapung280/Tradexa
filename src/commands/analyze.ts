import type { CommandContext, Context } from "grammy";
import type { AiProvider } from "../ai/types.js";
import { explainTechnicalReport, explanationEvidence, explanationMessages } from "../analysis/explain.js";
import { formatInstrumentAnalysis } from "../analysis/formatTechnical.js";
import { analyzeInstrument, type AnalysisOptions, type InstrumentAnalysis } from "../analysis/technical.js";
import { createUnavailableReport, formatAnalysisReport } from "../analysis/report.js";
import type { TokenBudget } from "../budget/ledger.js";
import { splitTelegramText } from "../chat/split.js";
import { logger } from "../config/logger.js";

export function renderAnalyzeReply(): string {
  return formatAnalysisReport(createUnavailableReport());
}

export type AnalyzeRuntime = AnalysisOptions & {
  provider?: AiProvider | null;
  budget?: TokenBudget;
  chatId?: number;
  splitLimit?: number;
  nowMs?: number;
};

export async function buildAnalyzeReplies(
  argument?: string,
  runtime: AnalyzeRuntime = {},
): Promise<string[]> {
  const token = argument?.trim().split(/\s+/)[0] ?? "";
  if (token.length === 0) {
    return splitTelegramText(renderAnalyzeReply(), runtime.splitLimit);
  }

  const analysis = analyzeInstrument(token, {
    ...(runtime.root !== undefined ? { root: runtime.root } : {}),
    ...(runtime.asOfMs !== undefined ? { asOfMs: runtime.asOfMs } : {}),
    ...(runtime.lookbackBars !== undefined ? { lookbackBars: runtime.lookbackBars } : {}),
    ...(runtime.cache !== undefined ? { cache: runtime.cache } : {}),
    ...(runtime.pivotLeft !== undefined ? { pivotLeft: runtime.pivotLeft } : {}),
    ...(runtime.pivotRight !== undefined ? { pivotRight: runtime.pivotRight } : {}),
    ...(runtime.gapClassifier !== undefined ? { gapClassifier: runtime.gapClassifier } : {}),
  });
  let text = formatInstrumentAnalysis(analysis);
  const hasCalculatedSection =
    analysis.kind === "report" && analysis.sections.some((section) => section.status === "ok");
  if (hasCalculatedSection && analysis.kind === "report" && runtime.provider) {
    text = await appendExplanation(text, analysis, runtime.provider, runtime);
  }
  return splitTelegramText(text, runtime.splitLimit);
}

export async function handleAnalyze(
  ctx: CommandContext<Context>,
  runtime: AnalyzeRuntime = {},
): Promise<void> {
  if (runtime.provider) {
    void ctx.replyWithChatAction("typing").catch(() => {
      // Typing is best-effort.
    });
  }
  try {
    const argument = typeof ctx.match === "string" ? ctx.match : "";
    const parts = await buildAnalyzeReplies(argument, {
      ...runtime,
      ...(ctx.chat?.id !== undefined ? { chatId: ctx.chat.id } : {}),
    });
    for (const part of parts) {
      await ctx.reply(part);
    }
  } catch (error: unknown) {
    logger.error({ err: error, chatId: ctx.chat?.id ?? null }, "Technical analysis failed");
    await ctx.reply("Technical analysis could not be completed. No prices were estimated.");
  }
}

async function appendExplanation(
  report: string,
  analysis: Extract<InstrumentAnalysis, { kind: "report" }>,
  provider: AiProvider,
  runtime: AnalyzeRuntime,
): Promise<string> {
  const evidence = explanationEvidence(analysis);
  const messages = explanationMessages(evidence);
  const finish = (result: Awaited<ReturnType<typeof explainTechnicalReport>>): string => {
    if (!result.accepted.ok) {
      const reason = result.accepted.reason === "empty"
        ? "Explanation was empty. The calculated report is unchanged."
        : "Explanation rejected because it did not match the calculated evidence. The calculated report is unchanged.";
      return `${report}\n\n${reason}`;
    }
    return `${report}\n\nExplanation:\n${result.accepted.text}`;
  };

  if (runtime.budget !== undefined && runtime.chatId !== undefined) {
    const reserved = await runtime.budget.reserve(runtime.chatId, messages);
    if (!reserved.ok) {
      return `${report}\n\nExplanation skipped: the daily token budget is exhausted. The calculated report above is unchanged.`;
    }
    try {
      const result = await explainTechnicalReport(provider, evidence, reserved.maxOutputTokens);
      await runtime.budget.settle(reserved.reservationId, {
        chatId: runtime.chatId,
        provider: provider.name,
        model: provider.model,
        inputTokens: result.inputTokens,
        outputTokens: result.outputTokens,
        ...(runtime.nowMs !== undefined ? { createdAt: runtime.nowMs } : {}),
      });
      return finish(result);
    } catch (error: unknown) {
      await runtime.budget.release(reserved.reservationId);
      logger.error({ err: error, chatId: runtime.chatId }, "Technical explanation failed");
      return `${report}\n\nExplanation unavailable. The calculated report above is unchanged.`;
    }
  }

  try {
    return finish(await explainTechnicalReport(provider, evidence, 600));
  } catch (error: unknown) {
    logger.error({ err: error }, "Technical explanation failed");
    return `${report}\n\nExplanation unavailable. The calculated report above is unchanged.`;
  }
}
