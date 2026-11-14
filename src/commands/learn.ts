import type { CommandContext, Context } from "grammy";
import { findTopic, formatTopic, formatTopicList } from "../education/topics.js";

export function renderLearnReply(argument: string): string {
  const id = argument.trim();
  if (id.length === 0) {
    return formatTopicList();
  }

  const topic = findTopic(id);
  if (topic === undefined) {
    return [`I don't have a lesson named "${id}".`, "", formatTopicList()].join("\n");
  }

  return formatTopic(topic);
}

export async function handleLearn(ctx: CommandContext<Context>): Promise<void> {
  const argument = typeof ctx.match === "string" ? ctx.match : "";
  await ctx.reply(renderLearnReply(argument));
}
