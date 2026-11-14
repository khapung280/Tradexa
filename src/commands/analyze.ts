import type { CommandContext, Context } from "grammy";
import { createUnavailableReport, formatAnalysisReport } from "../analysis/report.js";

export function renderAnalyzeReply(): string {
  return formatAnalysisReport(createUnavailableReport());
}

export async function handleAnalyze(ctx: CommandContext<Context>): Promise<void> {
  await ctx.reply(renderAnalyzeReply());
}
