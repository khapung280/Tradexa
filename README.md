# Tradexa

Phase 2 Telegram AI chatbot for Forex assistance. Tradexa answers private-chat messages with an OpenAI-compatible model (Google Gemini by default), keeps a short SQLite history per chat, and enforces daily token budgets.

## Features

- `/start` and `/help` commands
- AI replies in private Telegram chats (not groups)
- Provider-independent AI adapter with an OpenAI-compatible Gemini client
- Last 20 messages stored per Telegram chat ID in SQLite
- Daily UTC token limits (global and per chat), plus a max output-token cap per request
- Structured logs that redact bot tokens and API keys

## Layout

- `src/bot.ts` — loads the environment, opens SQLite, connects to Telegram, and shuts down safely
- `src/commands/` — `/start`, `/help`, and private-text AI handling
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

Do not commit real tokens or API keys.

## Run

From the project folder:

```bash
npm install
npm start
```

Then open a private chat with the bot:

- `/start` — welcome message
- `/help` — command list
- Send a normal text message to talk to Tradexa (requires AI configuration)

Stop the bot with Ctrl+C.

## Scripts

- `npm start` — run the bot from `src/` with `tsx`
- `npm run build` — compile TypeScript from `src/` into `dist/`
- `node dist/bot.js` — run the compiled bot from the project folder
- `npm test` — run Vitest
