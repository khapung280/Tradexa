import { config as loadDotenv } from "dotenv";
import { createServer } from "node:http";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { createAiProvider } from "../ai/index.js";
import { TokenBudget } from "../budget/ledger.js";
import { readAiConfig, readBudgetConfig, readRiskLimits } from "../config/env.js";
import { openChatStore } from "../db/store.js";
import { handleDashboardRequest } from "./api.js";

const FILES: Record<string, { file: string; type: string }> = {
  "/": { file: "index.html", type: "text/html; charset=utf-8" },
  "/styles.css": { file: "styles.css", type: "text/css; charset=utf-8" },
  "/app.js": { file: "app.js", type: "text/javascript; charset=utf-8" },
};

const SECURITY_HEADERS = {
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "content-security-policy": "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
};

export function dashboardAsset(pathname: string): { file: string; type: string } | null {
  return FILES[pathname] ?? null;
}

const PUBLIC_ROOT = resolve("src/web/public");

export function startDashboard(requestedPort?: number): void {
  const dotenvResult = loadDotenv({ quiet: true });
  if (dotenvResult.error !== undefined && dotenvResult.error.code !== "ENOENT") {
    throw new Error(`Could not load the .env file (${dotenvResult.error.code}).`);
  }
  const requested = process.env.DASHBOARD_PORT?.trim() ?? "";
  const port = requestedPort ?? (/^[0-9]+$/.test(requested) ? Number(requested) : 8787);
  const host = "127.0.0.1";
  const token = process.env.DASHBOARD_TOKEN?.trim() ?? "";
  const store = openChatStore();
  const ai = readAiConfig(process.env);
  const provider = ai === null ? null : createAiProvider(ai);
  const budget = new TokenBudget(store, readBudgetConfig(process.env));
  const riskLimits = readRiskLimits(process.env);
  const server = createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://127.0.0.1");
    const peer = req.socket.remoteAddress ?? "";
    if (url.pathname.startsWith("/api/")) {
      const result = await handleDashboardRequest({
        method: req.method ?? "GET",
        path: url.pathname,
        search: url.searchParams,
        peer,
        authorization: typeof req.headers.authorization === "string" ? req.headers.authorization : null,
        host: typeof req.headers.host === "string" ? req.headers.host : "",
        origin: typeof req.headers.origin === "string" ? req.headers.origin : null,
        nowMs: Date.now(),
        token: token.length > 0 ? token : null,
        store,
        provider,
        budget,
        riskLimits,
      });
      res.writeHead(result.status, { "content-type": result.contentType, "cache-control": "no-store", ...SECURITY_HEADERS });
      res.end(result.body);
      return;
    }
    const asset = dashboardAsset(url.pathname);
    if (asset === null) {
      res.writeHead(404, { "content-type": "text/plain; charset=utf-8", ...SECURITY_HEADERS });
      res.end("Not found");
      return;
    }
    res.writeHead(200, { "content-type": asset.type, "cache-control": "no-store", ...SECURITY_HEADERS });
    res.end(readFileSync(resolve(PUBLIC_ROOT, asset.file)));
  });
  server.listen(port, host, () => {
    console.log(`Tradexa dashboard listening on http://${host}:${port}`);
  });
}

if (process.argv[1]?.endsWith("server.ts") || process.argv[1]?.endsWith("server.js")) {
  startDashboard();
}
