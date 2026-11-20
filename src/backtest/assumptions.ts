/**
 * Execution model.
 *
 * Candles are Dukascopy bid prices. Ask is modeled as bid + a constant
 * configured spread. This is not a historical variable spread and it is not
 * a live broker fill.
 *
 * Stop and target distances are measured from the entry candle's bid open,
 * not from the spread-adjusted fill.
 * A long stop and a long target are bid prices.
 * A short stop and a short target are ask prices at that same pip distance.
 * With a constant spread, a short stop ask of bidOpen + N is touched when
 * bid high >= bidOpen + N - spread. A short target ask of bidOpen - N is
 * touched when bid low <= bidOpen - N - spread.
 *
 * A buy enters at ask + entry slippage and exits at bid - exit slippage.
 * A sell enters at bid - entry slippage and exits at ask + exit slippage.
 * Spread is charged once: on the long entry, or inside the short exit ask.
 * It is not charged a second time.
 *
 * A normal stop can lose the configured distance plus that side's spread and
 * slippage. Risk-percent sizing uses that expected loss, plus both commissions.
 * A gap can lose more. The engine does not promise a maximum loss.
 *
 * A signal at a candle close fills at the next candle's open.
 * M1 coverage requires every expected minute, starting at the parent open.
 * A count of rows is not enough. If that path is incomplete, the parent bar
 * is used with the same bid and ask rules. If one bar touches both stop and
 * target, the stop is filled. Within a bar, drawdown assumes the adverse
 * print happens while the position is still open. That is not a tick path.
 *
 * An unclassified hole cancels a pending signal and closes an open position
 * at the next real open. No candle is invented. The default classifier does
 * not treat a weekend as a verified closure.
 *
 * EMA 20/50/200 defaults to continuous entries: it can enter again after a
 * stop while the stack remains valid. trendentry=transition enters only when
 * the stack newly becomes valid.
 *
 * Open trades are not closed profit. Leverage, margin, and swaps are not
 * modeled. Pip size and contract size come from the /risk pair table.
 */
export const EXECUTION_ASSUMPTIONS = [
  "Historical simulation only. No live order is sent. Results are not future performance.",
  "Candles are Dukascopy bids. Ask is bid plus the configured constant spread. This is not a variable historical spread or a broker fill.",
  "A buy pays ask plus entry slippage and sells at bid minus exit slippage. A sell receives bid minus entry slippage and buys back at ask plus exit slippage.",
  "Stop and target distances are measured from the entry bid open. Long levels are bid prices. Short levels are ask prices.",
  "Spread is applied once, on the long entry or inside the short ask exit. It is not applied twice.",
  "If one execution bar touches both stop and target, the stop is filled.",
  "A gap through a long stop fills at the opened bid. A gap through a short stop fills at the opened ask plus exit slippage.",
  "A normal stop is what risk-percent sizing reserves. A gap can lose more than that reserve.",
  "Maximum drawdown includes the adverse bid for a long and the adverse ask for a short on each execution bar. The adverse print is assumed to happen before the exit in that bar. This is not tick-accurate.",
  "Missing candles are not invented. An unclassified hole cancels a pending signal and closes an open position at the next real open.",
  "The default gap classifier does not call a weekend a verified market closure.",
  "M1 is used only when every expected minute from the parent open is present. Otherwise the parent bar is used.",
  "Open trades are not counted as closed profit. Net profit is realized cash only.",
  "Leverage, margin, swaps, and broker-specific execution are not modeled.",
  "EMA 20/50/200 re-enters while its stack remains valid unless trendentry=transition.",
  "D1 volume is not used. Its cross-timeframe meaning remains unverified.",
] as const;
