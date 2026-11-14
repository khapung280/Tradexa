import type { CommandContext, Context } from "grammy";
import type { ChatStore } from "../db/store.js";
import { calculateRisk } from "../risk/calculate.js";
import {
  assertCanCalculate,
  getDailyLossStatus,
  recordLoss,
  type DailyLossStatus,
} from "../risk/kill.js";
import { DEFAULT_RISK_LIMITS, type RiskLimits } from "../risk/limits.js";
import { findPair } from "../risk/pairs.js";
import type { RiskRequest, RiskResult } from "../risk/types.js";

export const RISK_DISCLAIMER =
  "This is a position-size illustration only. Tradexa does not place orders, give buy/sell signals, or provide financial advice.";

export const RISK_LOSS_NOTE =
  "Losses are user-reported. Tradexa does not receive P/L from a broker or live trading account.";

export type RiskLossContext = {
  store: ChatStore;
  chatId: number;
  nowMs: number;
  messageId?: number;
};

export const RISK_USAGE = [
  "Usage:",
  "/risk EURUSD 10000 USD 1 50",
  "/risk EURGBP 10000 USD 1 50 1.25",
  "/risk loss 50 USD",
  "/risk status",
  "",
  "Arguments: pair balance account-currency risk-percent stop-loss-pips [quote-to-account-rate]",
  "The conversion rate is account-currency units per one quote-currency unit. Tradexa does not invent exchange rates.",
  "",
  RISK_LOSS_NOTE,
  RISK_DISCLAIMER,
].join("\n");

const NUMBER_PATTERN = /^[+-]?(?:\d+\.?\d*|\.\d+)$/;

export type ParsedRiskArgs =
  | { ok: true; request: RiskRequest }
  | { ok: false; error: "invalid_arguments" };

export function parseRiskArgs(argument: string): ParsedRiskArgs {
  const tokens = argument.trim().split(/\s+/).filter((token) => token.length > 0);
  if (tokens.length !== 5 && tokens.length !== 6) {
    return { ok: false, error: "invalid_arguments" };
  }

  const pair = tokens[0];
  const balanceToken = tokens[1];
  const accountCurrency = tokens[2];
  const riskToken = tokens[3];
  const stopToken = tokens[4];
  if (
    pair === undefined ||
    balanceToken === undefined ||
    accountCurrency === undefined ||
    riskToken === undefined ||
    stopToken === undefined
  ) {
    return { ok: false, error: "invalid_arguments" };
  }

  const accountBalance = parseNumberToken(balanceToken);
  const riskPercent = parseNumberToken(riskToken);
  const stopLossPips = parseNumberToken(stopToken);
  if (
    accountBalance === undefined ||
    riskPercent === undefined ||
    stopLossPips === undefined
  ) {
    return { ok: false, error: "invalid_arguments" };
  }

  const request: RiskRequest = {
    pair,
    accountBalance,
    accountCurrency,
    riskPercent,
    stopLossPips,
  };

  if (tokens.length === 6) {
    const rateToken = tokens[5];
    if (rateToken === undefined) {
      return { ok: false, error: "invalid_arguments" };
    }
    const quoteToAccountRate = parseNumberToken(rateToken);
    if (quoteToAccountRate === undefined) {
      return { ok: false, error: "invalid_arguments" };
    }
    request.quoteToAccountRate = quoteToAccountRate;
  }

  return { ok: true, request };
}

function parseNumberToken(raw: string): number | undefined {
  if (!NUMBER_PATTERN.test(raw)) {
    return undefined;
  }
  const value = Number(raw);
  return Number.isFinite(value) ? value : undefined;
}

function commandTokens(argument: string): string[] {
  return argument.trim().split(/\s+/).filter((token) => token.length > 0);
}

function formatAmount(value: number): string {
  if (Number.isInteger(value)) {
    return String(value);
  }
  return value.toFixed(2);
}

function formatLots(value: number): string {
  return value.toFixed(2);
}

function killLabel(killed: boolean): string {
  return killed ? "ON" : "OFF";
}

export function formatKillSwitchReply(maxDailyLoss: number): string {
  return [
    `This chat has reached today's reported-loss limit of ${formatAmount(maxDailyLoss)}.`,
    "Position-size calculations are paused until 00:00 UTC.",
    "Tradexa will not show a lot size while the daily loss kill switch is on.",
    RISK_LOSS_NOTE,
    "",
    RISK_DISCLAIMER,
  ].join("\n");
}

function formatStatusReply(status: DailyLossStatus): string {
  const reported =
    status.currency === null
      ? `Reported loss: ${formatAmount(status.totalLoss)}`
      : `Reported loss: ${formatAmount(status.totalLoss)} ${status.currency}`;
  return [
    "Tradexa daily loss (UTC)",
    "",
    reported,
    `Currency: ${status.currency ?? "not set"}`,
    `Limit: ${formatAmount(status.maxDailyLoss)}`,
    `Remaining: ${formatAmount(status.remaining)}`,
    `Kill switch: ${killLabel(status.killed)}`,
    "",
    RISK_LOSS_NOTE,
  ].join("\n");
}

function formatLossRecorded(status: DailyLossStatus, amount: number, currency: string): string {
  return [
    `Recorded a reported loss of ${formatAmount(amount)} ${currency}.`,
    `Today's total: ${formatAmount(status.totalLoss)} ${status.currency ?? currency}`,
    `Remaining: ${formatAmount(status.remaining)}`,
    `Kill switch: ${killLabel(status.killed)}`,
    "",
    RISK_LOSS_NOTE,
  ].join("\n");
}

function renderStatusReply(limits: RiskLimits, lossContext: RiskLossContext): string {
  const status = getDailyLossStatus(
    lossContext.store,
    lossContext.chatId,
    limits.maxDailyLoss,
    lossContext.nowMs,
  );
  return formatStatusReply(status);
}

function renderLossReply(
  tokens: string[],
  limits: RiskLimits,
  lossContext: RiskLossContext | undefined,
): string {
  if (tokens.length !== 3) {
    return ["Use /risk loss 50 USD", "", RISK_USAGE].join("\n");
  }
  const amountToken = tokens[1];
  const currencyToken = tokens[2];
  if (amountToken === undefined || currencyToken === undefined) {
    return ["Use /risk loss 50 USD", "", RISK_USAGE].join("\n");
  }
  const amount = parseNumberToken(amountToken);
  const currency = currencyToken.trim();
  if (amount === undefined || currency.length === 0) {
    return ["Amount must be a positive number and currency is required.", "", RISK_USAGE].join(
      "\n",
    );
  }
  if (lossContext === undefined) {
    return ["This chat cannot record a loss right now.", "", RISK_USAGE].join("\n");
  }
  if (lossContext.messageId === undefined) {
    return ["This message cannot be recorded as a loss.", "", RISK_USAGE].join("\n");
  }

  const result = recordLoss(
    lossContext.store,
    {
      chatId: lossContext.chatId,
      amount,
      currency,
      messageId: lossContext.messageId,
    },
    limits.maxDailyLoss,
    lossContext.nowMs,
  );

  if (result.status === "invalid_amount") {
    return [
      "Amount must be a positive number. Profits and negative values are not recorded.",
      "",
      RISK_LOSS_NOTE,
    ].join("\n");
  }
  if (result.status === "duplicate") {
    const status = getDailyLossStatus(
      lossContext.store,
      lossContext.chatId,
      limits.maxDailyLoss,
      lossContext.nowMs,
    );
    return [
      "This Telegram message was already recorded. Today's total is unchanged.",
      `Today's total: ${formatAmount(status.totalLoss)} ${status.currency ?? currency}`,
      `Kill switch: ${killLabel(status.killed)}`,
      "",
      RISK_LOSS_NOTE,
    ].join("\n");
  }
  if (result.status === "currency_mismatch") {
    return [
      `Today's losses for this chat are in ${result.day.currency}. A different currency was not added.`,
      "Tradexa does not convert currencies or invent exchange rates.",
      "",
      RISK_LOSS_NOTE,
    ].join("\n");
  }

  const status = getDailyLossStatus(
    lossContext.store,
    lossContext.chatId,
    limits.maxDailyLoss,
    lossContext.nowMs,
  );
  return formatLossRecorded(status, amount, currency.toUpperCase());
}

function formatRiskSuccess(result: Extract<RiskResult, { ok: true }>): string {
  const conversionLine =
    result.conversion === "not_required"
      ? `Conversion rate: 1 (${result.accountCurrency} matches quote currency)`
      : `Conversion rate: ${formatAmount(result.quoteToAccountRate)} ${result.accountCurrency} per 1 ${result.quote}`;

  return [
    "Tradexa risk calculation",
    "",
    `Pair: ${result.pair}`,
    `Account: ${formatAmount(result.accountBalance)} ${result.accountCurrency}`,
    `Risk: ${formatAmount(result.riskPercent)}% = ${formatAmount(result.riskAmount)} ${result.accountCurrency}`,
    `Stop-loss: ${formatAmount(result.stopLossPips)} pips`,
    `Pip value: ${formatAmount(result.pipValueAccountPerLot)} ${result.accountCurrency} per standard lot`,
    conversionLine,
    `Position size: ${formatLots(result.positionSizeLots)} standard lots`,
    "",
    RISK_DISCLAIMER,
  ].join("\n");
}

function formatRiskFailure(
  error: Exclude<RiskResult, { ok: true }>["error"],
  request: RiskRequest,
  limits: RiskLimits,
): string {
  switch (error) {
    case "invalid_balance":
      return ["Account balance must be a positive number.", "", RISK_USAGE].join("\n");
    case "invalid_risk_percent":
      return ["Risk percentage must be a positive number.", "", RISK_USAGE].join("\n");
    case "invalid_stop_loss_pips":
      return ["Stop-loss pips must be a positive number.", "", RISK_USAGE].join("\n");
    case "unknown_pair":
      return [
        `I don't have a catalog entry for "${request.pair.trim().toUpperCase()}". Unknown pairs are not sized.`,
        "",
        RISK_USAGE,
      ].join("\n");
    case "conversion_required": {
      const pair = findPair(request.pair);
      const quote = pair?.quote ?? "the quote currency";
      return [
        `A quote-to-account conversion rate is required because the account currency is not ${quote}.`,
        "Add the rate as the last argument: account-currency units per one quote-currency unit.",
        "Example: /risk EURGBP 10000 USD 1 50 1.25",
        "Tradexa does not invent exchange rates.",
        "",
        RISK_USAGE,
      ].join("\n");
    }
    case "invalid_conversion_rate":
      return [
        "The conversion rate must be a positive number (account-currency units per one quote-currency unit).",
        "Tradexa does not invent exchange rates.",
        "",
        RISK_USAGE,
      ].join("\n");
    case "risk_percent_limit":
      return [
        `Risk percentage ${formatAmount(request.riskPercent)}% exceeds the maximum of ${formatAmount(limits.maxRiskPercent)}%.`,
        "Tradexa will not reduce the requested risk. Use a lower risk percentage and try again.",
        "",
        RISK_DISCLAIMER,
      ].join("\n");
    case "lot_size_limit":
      return [
        `This calculation exceeds the maximum lot size of ${formatAmount(limits.maxLots)}.`,
        "Tradexa will not reduce the position size. Use a lower risk percentage or a wider stop-loss.",
        "",
        RISK_DISCLAIMER,
      ].join("\n");
  }
}

export function renderRiskReply(
  argument: string,
  limits: RiskLimits = DEFAULT_RISK_LIMITS,
  lossContext?: RiskLossContext,
): string {
  const tokens = commandTokens(argument);
  const head = tokens[0]?.toLowerCase();

  if (head === "status") {
    if (tokens.length !== 1) {
      return RISK_USAGE;
    }
    if (lossContext === undefined) {
      return ["This chat cannot read daily loss status right now.", "", RISK_USAGE].join("\n");
    }
    return renderStatusReply(limits, lossContext);
  }

  if (head === "loss") {
    return renderLossReply(tokens, limits, lossContext);
  }

  const parsed = parseRiskArgs(argument);
  if (!parsed.ok) {
    return RISK_USAGE;
  }

  if (lossContext !== undefined) {
    const gate = assertCanCalculate(
      lossContext.store,
      lossContext.chatId,
      limits.maxDailyLoss,
      lossContext.nowMs,
    );
    if (!gate.ok) {
      return formatKillSwitchReply(limits.maxDailyLoss);
    }
  }

  const result = calculateRisk(parsed.request, limits);
  if (!result.ok) {
    return formatRiskFailure(result.error, parsed.request, limits);
  }
  return formatRiskSuccess(result);
}

export async function handleRisk(
  ctx: CommandContext<Context>,
  limits: RiskLimits = DEFAULT_RISK_LIMITS,
  store?: ChatStore,
  now: () => number = Date.now,
): Promise<void> {
  const argument = typeof ctx.match === "string" ? ctx.match : "";
  const chatId = ctx.chat?.id;
  const messageId = ctx.message?.message_id;
  if (store !== undefined && chatId !== undefined && Number.isSafeInteger(chatId)) {
    const lossContext: RiskLossContext = { store, chatId, nowMs: now() };
    if (messageId !== undefined && Number.isSafeInteger(messageId)) {
      lossContext.messageId = messageId;
    }
    await ctx.reply(renderRiskReply(argument, limits, lossContext));
    return;
  }
  await ctx.reply(renderRiskReply(argument, limits));
}
