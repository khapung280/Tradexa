# Tradexa

Phase 1 Telegram bot for the Tradexa Node.js and TypeScript project.

## Layout

- `src/bot.ts` — loads the environment, connects to Telegram, and shuts down safely.
- `src/commands/` — `/start` and `/help` handlers.
- `src/config/` — environment loading and structured logging.
- `tests/` — automated tests.
- `.env.example` — template for local environment variables. Copy it to `.env`.

## Configure

1. Copy `.env.example` to `.env` in the project folder.
2. Set `BOT_TOKEN` to the token from [BotFather](https://t.me/BotFather).
3. Leave `.env` uncommitted. It is listed in `.gitignore`.

## Scripts

- `npm start` — run the bot from the project folder.
- `npm run build` — compile TypeScript from `src/` into `dist/`.
- `node dist/bot.js` — run the compiled bot from the project folder.
- `npm test` — run Vitest.
