export function estimateTextTokens(text: string): number {
  return Math.max(1, Math.ceil(text.length / 4));
}

export function estimateMessagesTokens(messages: readonly { content: string }[]): number {
  return messages.reduce((sum, message) => sum + estimateTextTokens(message.content), 0);
}
