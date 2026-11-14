export const TELEGRAM_MESSAGE_LIMIT = 4096;

export function splitTelegramText(
  text: string,
  limit: number = TELEGRAM_MESSAGE_LIMIT,
): string[] {
  if (limit < 1) {
    throw new Error("Telegram message limit must be at least 1.");
  }
  if (text.length <= limit) {
    return [text];
  }

  const parts: string[] = [];
  let remaining = text;
  const minBreak = Math.floor(limit / 2);

  while (remaining.length > limit) {
    const window = remaining.slice(0, limit);
    let splitAt = window.lastIndexOf("\n");
    if (splitAt < minBreak) {
      splitAt = window.lastIndexOf(" ");
    }
    if (splitAt < minBreak) {
      splitAt = limit;
    }

    const part = remaining.slice(0, splitAt).trimEnd();
    if (part.length === 0) {
      parts.push(remaining.slice(0, limit));
      remaining = remaining.slice(limit);
      continue;
    }

    parts.push(part);
    remaining = remaining.slice(splitAt).trimStart();
  }

  if (remaining.length > 0) {
    parts.push(remaining);
  }
  return parts;
}
