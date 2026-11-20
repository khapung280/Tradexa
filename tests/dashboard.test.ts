import { describe, expect, it } from "vitest";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { handleDashboardRequest } from "../src/web/api.ts";
import { dashboardAsset } from "../src/web/server.ts";
import { openChatStore } from "../src/db/store.ts";
import { parsePerformanceCatalog } from "../src/decision/performance.ts";

const NOW = Date.UTC(2026, 10, 20, 12, 0, 0);

function request(path: string, extras: Partial<Parameters<typeof handleDashboardRequest>[0]> = {}) {
  const url = new URL(path, "http://127.0.0.1");
  return handleDashboardRequest({
    method: "GET",
    path: url.pathname,
    search: url.searchParams,
    peer: "127.0.0.1",
    authorization: null,
    nowMs: NOW,
    token: null,
    ...extras,
  });
}

describe("dashboard", () => {
  it("rejects remote peers, bad symbols, bad timeframes, and unauthenticated posts", async () => {
    expect((await request("/api/overview", { peer: "203.0.113.8" })).status).toBe(403);
    expect((await request("/api/overview?symbol=BTCUSD")).status).toBe(400);
    expect((await request("/api/candles?timeframe=M2")).status).toBe(400);
    expect((await request("/api/candles?timeframe=H1&limit=1")).status).toBe(400);
    const posted = await request("/api/decision", { method: "POST" });
    expect(posted.status).toBe(403);
    expect(posted.body).not.toContain("AI_API_KEY");
    const history = await request("/api/decisions");
    expect(history.status).toBe(403);
    const privateRisk = await request("/api/risk?chatId=1");
    expect(privateRisk.status).toBe(403);
  });

  it("serves historical status, performance, and settings without secrets", async () => {
    const previous = process.env.AI_API_KEY;
    process.env.AI_API_KEY = "dashboard-sentinel-secret";
    try {
      const overview = await request("/api/overview");
      const settings = await request("/api/settings");
      const performance = await request("/api/performance");
      expect(overview.status).toBe(200);
      expect(overview.body).toContain("HISTORICAL");
      expect(overview.body).toContain("\"ordersEnabled\":false");
      expect(settings.body).not.toContain("dashboard-sentinel-secret");
      expect(settings.body).toContain("\"secretsIncluded\":false");
      const catalog = JSON.parse(performance.body) as { available: boolean; rows: { sample: string; netProfit: number }[]; disclaimer: string };
      expect(catalog.available).toBe(true);
      expect(catalog.disclaimer).toContain("not future performance");
      expect(catalog.rows.some((row) => row.sample === "out_of_sample")).toBe(true);
      expect(catalog.rows.some((row) => row.netProfit === -435.3)).toBe(true);
    } finally {
      if (previous === undefined) {
        delete process.env.AI_API_KEY;
      } else {
        process.env.AI_API_KEY = previous;
      }
    }
  });

  it("reads stored decisions and keeps historical candles distinct from live data", async () => {
    const dir = mkdtempSync(join(tmpdir(), "tradexa-dashboard-"));
    const store = openChatStore(join(dir, "tradexa.db"));
    try {
      const candles = await request("/api/candles?timeframe=H1&limit=30");
      const body = JSON.parse(candles.body) as { dataMode: string; source: string; candles: { timeMs: number }[] };
      expect(body.dataMode).toBe("HISTORICAL");
      expect(body.source).toContain("Not a live feed");
      expect(body.candles.length).toBeGreaterThan(20);
      expect(body.candles[0]?.timeMs).toBeLessThan(body.candles[body.candles.length - 1]?.timeMs ?? 0);
      const locked = await request("/api/decisions", { store, token: "local-test-token" });
      expect(locked.status).toBe(403);
      const decisions = await request("/api/decisions", {
        store,
        token: "local-test-token",
        authorization: "Bearer local-test-token",
      });
      expect(JSON.parse(decisions.body)).toEqual({ decisions: [] });
    } finally {
      store.close();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects a foreign host, a foreign origin, a wrong token, and path traversal", async () => {
    expect((await request("/api/overview", { host: "evil.example" })).status).toBe(403);
    expect((await request("/api/overview", { host: "" })).status).toBe(403);
    expect((await request("/api/overview", { origin: "https://evil.example" })).status).toBe(403);
    expect((await request("/api/overview", { host: "127.0.0.1:8787", origin: "http://127.0.0.1:8787" })).status).toBe(200);
    const wrong = await request("/api/decisions", { token: "correct-token", authorization: "Bearer wrong-token" });
    expect(wrong.status).toBe(403);
    const injected = await request("/api/overview?symbol=%3Cscript%3Ealert(1)%3C%2Fscript%3E");
    expect(injected.status).toBe(400);
    expect(injected.body).not.toContain("<script>");
    const sql = await request("/api/candles?timeframe=H1'%20OR%201=1");
    expect(sql.status).toBe(400);
    expect(sql.body).not.toContain("SQLITE");
    expect(dashboardAsset("/../.env")).toBeNull();
    expect(dashboardAsset("/app.js")?.file).toBe("app.js");
  });

  it("rejects a catalog that invents a source", () => {
    expect(parsePerformanceCatalog({ version: 1, source: "guess", disclaimer: "x", rows: [] }).ok).toBe(false);
  });
});
