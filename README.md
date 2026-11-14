# Tradexa

Phase 3 Telegram AI chatbot for Forex education and analysis concepts. Tradexa answers private-chat messages with an OpenAI-compatible model (Google Gemini by default), keeps a short SQLite history per chat, enforces daily token budgets, and teaches Forex with a static lesson catalog.

## Features

- `/start` and `/help` commands
- `/learn` — static beginner lessons (Forex basics, pips, lots, spreads, leverage, trend, support/resistance, price action)
- `/analyze` — chart-reading walkthrough with `dataStatus: unavailable` (no live market data)
- AI replies in private Telegram chats, using the education catalog as a trusted reference
- Last 20 messages stored per Telegram chat ID in SQLite
- Daily UTC token limits (global and per chat), plus a max output-token cap per request
- Structured logs that redact bot tokens and API keys
- No invented live prices, trading signals, or guaranteed predictions

## Layout

- `src/bot.ts` — loads the environment, opens SQLite, connects to Telegram, and shuts down safely
- `src/commands/` — `/start`, `/help`, `/learn`, `/analyze`, and private-text AI handling
- `src/education/` — static lesson catalog and AI system prompt
- `src/analysis/` — typed analysis report and chart-reading walkthrough
- `src/ai/` — provider interface and OpenAI-compatible client
- `src/chat/` — chat processing, timeouts, retries, and Telegram message splitting
- `src/budget/` — UTC daily token reservation and limits
- `src/db/` — SQLite messages and usage storage
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

Do not commit real tokens or API keys. Budget values in `.env` are what the running bot uses, not `.env.example`.

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
- Send a normal text message to talk to Tradexa (requires AI configuration)

Stop the bot with Ctrl+C.

## Scripts

- `npm start` — run the bot from `src/` with `tsx`
- `npm run build` — compile TypeScript from `src/` into `dist/`
- `node dist/bot.js` — run the compiled bot from the project folder
- `npm test` — run Vitest
