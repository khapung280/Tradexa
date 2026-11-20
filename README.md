# Tradexa

Telegram AI chatbot for Forex education, analysis concepts, position-size risk calculations, and trading-session context. Phases 6 through 9 add historical Dukascopy data, technical analysis, historical backtests, and a shadow-mode decision dashboard. Tradexa answers private-chat messages with an OpenAI-compatible model (Google Gemini by default), keeps a short SQLite history per chat, enforces daily token budgets, teaches Forex with a static lesson catalog, reports catalog session hours and London/New York overlap, and sizes positions from user-supplied inputs. It does not place orders.

## Phase status

- Phase 6 historical Dukascopy EURUSD data, including native M5, M15, M30, H1, H4, and D1: complete. Generated CSV datasets stay on disk and are not part of this repository. A news or calendar API is an optional future enhancement and is not required for Phase 6.
- Phase 7 technical analysis: complete.
- Phase 8 historical backtesting: complete. The figures are historical simulations, not live results and not future performance.
- Phase 9 shadow decision and local dashboard: complete. There is no broker connection and no order execution.
- Phase 10: not started.

## Features

- `/start` and `/help` commands
- `/learn` — static beginner lessons (Forex basics, pips, lots, spreads, leverage, trend, support/resistance, price action)
- `/analyze` — chart-reading walkthrough with `dataStatus: unavailable` (no live market data)
- `/backtest` — historical EURUSD strategy simulation on the local Dukascopy candles. It does not place a live order.
- `/decision` — shadow-mode assessment. Without a verified live quote the trading classification is WAIT. A historical note is labelled non-actionable. It does not place an order.
- `/timeframe` — supported chart timeframes (M1–D1)
- `/session` — current Forex session open/closed status and London/New York overlap
- `/risk` — position size from balance, risk percent, stop-loss pips, and an explicit quote-to-account rate when needed
- `/risk loss` — record a user-reported realized loss for this chat (UTC day)
- `/risk status` — today's reported loss, remaining room, and kill-switch ON/OFF
- Daily loss kill switch: when the reported-loss cap is reached, new `/risk` calculations are refused until 00:00 UTC
- AI replies in private Telegram chats, using the education catalog and current session-hours context as trusted references
- Last 20 messages stored per Telegram chat ID in SQLite
- Daily UTC token limits (global and per chat), plus a max output-token cap per request
- Structured logs that redact bot tokens and API keys
- No invented live prices, trading signals, guaranteed predictions, or broker/MT5 P/L

## Layout

- `src/bot.ts` — loads the environment, opens SQLite, connects to Telegram, and shuts down safely
- `src/commands/` — `/start`, `/help`, `/learn`, `/analyze`, `/timeframe`, `/session`, `/risk`, and private-text AI handling
- `src/market/` — timeframe catalog, session catalog, open/close detection, and overlap
- `src/education/` — static lesson catalog and AI system prompt
- `src/analysis/` — typed analysis report, indicators, and chart-reading walkthrough
- `src/backtest/` — historical simulation: strategies, execution, accounting, and performance
- `src/decision/` — shadow-mode evidence, AI assessment, decision gate, and stored decision records
- `src/risk/` — pair catalog, position-size math, risk limits, and daily-loss kill switch
- `src/ai/` — provider interface and OpenAI-compatible client
- `src/chat/` — chat processing, timeouts, retries, and Telegram message splitting
- `src/budget/` — UTC daily token reservation and limits
- `src/db/` — SQLite messages, AI usage, daily-loss storage, and additive decision records
- `src/config/` — environment loading and structured logging
- `tests/` — automated tests
- `.env.example` — template for local environment variables

## Configure

1. Copy `.env.example` to `.env` in the project folder.
2. Set `BOT_TOKEN` to the token from [BotFather](https://t.me/BotFather).
3. For AI replies, set `AI_API_KEY`, `AI_BASE_URL`, and `AI_MODEL` together. Omit all three to run the Telegram bot without AI.
4. Leave `.env` uncommitted. It is listed in `.gitignore`.
5. SQLite data is written to `data/tradexa.db`. The `data/` folder is gitignored.

### Environment variables

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `BOT_TOKEN` | Yes | — | Telegram bot token from BotFather |
| `AI_API_KEY` | With the other AI vars | — | Gemini / OpenAI-compatible API key |
| `AI_BASE_URL` | With the other AI vars | — | OpenAI-compatible base URL. For Gemini: `https://generativelanguage.googleapis.com/v1beta/openai/` |
| `AI_MODEL` | With the other AI vars | — | Model id, for example `gemini-2.0-flash` |
| `AI_DAILY_TOKEN_LIMIT` | No | `100000` | Bot-wide token cap per UTC day |
| `AI_CHAT_DAILY_TOKEN_LIMIT` | No | `20000` | Per-chat token cap per UTC day |
| `AI_MAX_OUTPUT_TOKENS` | No | `2048` | Maximum output tokens on each AI request |
| `RISK_MAX_PERCENT` | No | `2` | Maximum risk percent for `/risk` |
| `RISK_MAX_LOTS` | No | `2` | Maximum position size in standard lots |
| `RISK_MAX_DAILY_LOSS` | No | `100` | Maximum user-reported loss per chat per UTC day |

Do not commit real tokens or API keys. Values in `.env` are what the running bot uses, not `.env.example`.

## Run

From the project folder:

```bash
npm install
npm start
```

Then open a private chat with the bot:

- `/start` — welcome message
- `/help` — command list
- `/learn` — Forex lessons
- `/analyze` — how to read a chart (no live data)
- `/timeframe` — supported chart periods
- `/session` — session status and London/New York overlap
- `/risk EURUSD 10000 USD 1 50` — position size (example)
- `/risk loss 50 USD` — record a user-reported loss
- `/risk status` — today's reported loss and kill switch
- Send a normal text message to talk to Tradexa (requires AI configuration)

Stop the bot with Ctrl+C.

## Historical backtest

`/backtest EURUSD EMA_CROSSOVER H1` replays a strategy on the local EURUSD candles. M5 through D1 come from the native Dukascopy files. M1 comes from the production daily files and is used to see whether a stop or target was touched first. Higher timeframes are not rebuilt from M1.

A signal is decided at the candle close and filled at the next candle's open. The files are bid candles. Ask is the bid plus a constant spread. A long stop uses the bid. A short stop uses the ask. The default cost is a 1.0 pip spread and 0.2 pip slippage, with commission shown even when it is configured as zero. Risk-percent size reserves that normal stop cost. A gap can lose more. If one bar touches both the stop and the target, the stop is filled. An open trade at the end is not counted as closed profit. EMA 20/50/200 re-enters while its stack remains valid unless `trendentry=transition`.

Strategies are EMA 20/50 crossover, EMA 20/50/200 trend, RSI 14, MACD 12/26/9, and EMA plus RSI. Pip size and the 100,000-unit lot come from the same pair table as `/risk`. Leverage is not simulated. D1 volume is not used.

Example: `/backtest EURUSD RSI M15 2024-01-01 2024-03-01 risk=1 sl=30 rr=2 spread=1`

Add a strategy by implementing `onBar` in `src/backtest/strategies.ts`. It receives only the current closed bar.

## Shadow-mode decision

`/decision EURUSD` reads the existing native EURUSD candles, builds evidence from the Phase 7 technical engine, and stores a shadow-mode decision. `/decisions` lists the stored assessments for the chat.

Without a verified live quote, the published classification is `WAIT — LIVE MARKET DATA UNAVAILABLE`. With an accepted bid and ask, it is `WAIT — SHADOW MODE` because equity is unverified and this phase does not send an order. A separate line may show a historical research classification. That line is labelled `HISTORICAL RESEARCH ONLY — NOT A LIVE TRADING SIGNAL`. It is not an order and it is not a claim about future performance.

The model may explain the supplied evidence ids. It cannot create prices, stops, lot size, or a win probability, and it cannot override a failed gate. Entry, stop, target, and position size stay `NOT AVAILABLE`. A verified quote supplies bid, ask, and spread only. Account equity is not verified, and this phase has no approved stop formula. User-reported `/risk` losses are not broker P/L. Strategy performance is not estimated on each request.

Phase 9 does not connect a broker. A later demo-execution phase would need a separate verified live feed, a verified spread, a market calendar, account equity, and an explicit execution design. Those are not part of this phase.

## Local dashboard

`npm run dashboard` serves the shadow terminal at `http://127.0.0.1:8787`. It binds to loopback only. Pages read the native EURUSD candles, the Phase 8 performance catalog, and stored decisions. Historical candles stay labeled historical. A bid and ask appear only when a verified live quote is accepted. The dashboard cannot place an order.

Set `DASHBOARD_TOKEN` before opening decision history or running an assessment. The page does not reveal API keys. The bot and the dashboard can open the same SQLite file: the store uses WAL and waits up to 5 seconds for a lock. A write that is still blocked after that fails closed and does not replace an existing decision. Set `LIVE_QUOTE_PROVIDER=biquote` to request a EURUSD bid and ask from biquote. Leave it unset to keep quotes unavailable. A closed, stale, or invalid quote stays non-actionable, and historical candles stay labeled historical.

## Scripts

- `npm start` — run the bot from `src/` with `tsx`
- `npm run build` — compile TypeScript from `src/` into `dist/`
- `node dist/bot.js` — run the compiled bot from the project folder
- `npm test` — run Vitest
- `npm run dashboard` — local shadow dashboard on 127.0.0.1:8787
