import type { CommandContext, Context } from "grammy";

export function renderHelpReply(): string {
  return [
    "Tradexa commands:",
    "",
    "/start — Welcome message",
    "/help — Show this list",
    "/learn — List Forex lessons",
    "/learn pips — Open one lesson (example)",
    "/analyze — Chart-reading guide (no live market data)",
    "/analyze EURUSD — Historical technical report from native candles",
    "/backtest EURUSD EMA_CROSSOVER H1 — Historical strategy simulation (not a live order)",
    "/decision EURUSD — Shadow-mode assessment (not a live order)",
    "/decisions — Read stored shadow-mode assessments",
    "/timeframe — Supported chart timeframes (M1–D1)",
    "/session — Current Forex session status and London/New York overlap",
    "/risk EURUSD 10000 USD 1 50 — Position size (example)",
    "/risk loss 50 USD — Record a user-reported loss",
    "/risk status — Today's reported loss and kill switch",
    "",
    "Losses are user-reported. Tradexa does not receive P/L from a broker or live trading account.",
    "",
    "Send a command as a message in this chat.",
    "In a private chat you can also send a normal message to talk to Tradexa.",
  ].join("\n");
}

export async function handleHelp(ctx: CommandContext<Context>): Promise<void> {
  await ctx.reply(renderHelpReply());
}
