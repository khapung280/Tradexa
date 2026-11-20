const main = document.querySelector("#main");
const pages = {
  overview: renderOverview,
  market: renderMarket,
  decisions: renderDecisions,
  performance: renderPerformance,
  risk: renderRisk,
  settings: renderSettings,
};

document.querySelectorAll("nav button").forEach((button) => {
  button.addEventListener("click", () => {
    document.querySelectorAll("nav button").forEach((item) => item.classList.remove("active"));
    button.classList.add("active");
    show(button.dataset.page);
  });
});

show("overview");

async function show(page) {
  main.innerHTML = "<p class='muted'>Loading recorded data…</p>";
  try {
    await pages[page]();
  } catch (error) {
    main.innerHTML = `<p class="error">${escapeText(error.message)}</p>`;
  }
}

async function renderOverview() {
  const data = await getJson("/api/overview", true);
  const latest = data.latestDecision;
  main.innerHTML = `
    <section class="grid">
      ${card("Platform", "Shadow mode. Orders are off.")}
      ${card("Market data", quoteText(data))}
      ${card("Symbol", data.symbol)}
      ${card("Performance rows", String(data.performanceRows))}
      ${card("Latest trading classification", data.decisionAccess === "locked" ? "Locked until the dashboard token is entered in Settings." : latest ? `${latest.tradingLabel} at ${stamp(latest.createdAtMs)}` : "No stored decision yet.")}
      ${card("Risk gate", data.decisionAccess === "locked" ? "Locked with the decision history." : latest ? latest.risk : "No stored decision yet.")}
    </section>`;
}

async function renderMarket() {
  const preferred = localStorage.getItem("tradexa.timeframe") || "H1";
  main.innerHTML = `
    <section class="card">
      <label>Timeframe <select id="timeframe">${["M5", "M15", "M30", "H1", "H4", "D1"].map((item) => `<option ${item === preferred ? "selected" : ""}>${item}</option>`).join("")}</select></label>
      <p id="market-note" class="muted"></p>
    </section>
    <canvas id="chart" width="1100" height="360"></canvas>
    <section id="market-detail"></section>`;
  document.querySelector("#timeframe").addEventListener("change", loadMarket);
  await loadMarket();
}

async function loadMarket() {
  const timeframe = document.querySelector("#timeframe").value;
  localStorage.setItem("tradexa.timeframe", timeframe);
  const [candles, analysis, higher] = await Promise.all([
    getJson(`/api/candles?timeframe=${timeframe}&limit=120`),
    getJson(`/api/analysis?timeframe=${timeframe}`),
    getJson("/api/analysis?timeframe=H4"),
  ]);
  const last = candles.available ? candles.candles.at(-1) : null;
  document.querySelector("#market-note").textContent = last
    ? `${candles.dataMode}. ${candles.source} Last bar open ${stamp(last.timeMs)}. OHLC ${last.open} / ${last.high} / ${last.low} / ${last.close}.`
    : candles.reason ?? "No closed candles were available. None were synthesized.";
  drawCandles(candles.candles ?? []);
  const detail = document.querySelector("#market-detail");
  if (!analysis.available) {
    detail.innerHTML = `<p class="error">${escapeText(analysis.reason)}</p>`;
    return;
  }
  const indicators = analysis.indicators ?? {};
  detail.innerHTML = `<section class="grid">
    ${card(`${timeframe} trend`, `${analysis.trend}. Close ${analysis.close}. Mode ${analysis.dataMode}.`)}
    ${card("Indicators", `EMA 20/50/200 ${num(indicators.ema20, 5)} / ${num(indicators.ema50, 5)} / ${num(indicators.ema200, 5)}. RSI ${num(indicators.rsi14, 2)}. MACD ${num(indicators.macd, 5)}. ATR ${num(indicators.atr14, 5)}.`)}
    ${card("Zones", `Support ${zones(analysis.support)}. Resistance ${zones(analysis.resistance)}.`)}
    ${card("H4 trend", higher.available ? `${higher.trend}. Historical, not a live alignment signal.` : higher.reason)}
  </section>`;
}

function drawCandles(bars) {
  const canvas = document.querySelector("#chart");
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  if (bars.length === 0) return;
  const highs = bars.map((bar) => bar.high);
  const lows = bars.map((bar) => bar.low);
  const max = Math.max(...highs);
  const min = Math.min(...lows);
  const span = max - min || 1;
  const width = canvas.width / bars.length;
  bars.forEach((bar, index) => {
    const x = index * width + width / 2;
    const y = (value) => 16 + ((max - value) / span) * (canvas.height - 32);
    context.strokeStyle = bar.close >= bar.open ? "#9dba7a" : "#d38b7a";
    context.fillStyle = context.strokeStyle;
    context.beginPath();
    context.moveTo(x, y(bar.high));
    context.lineTo(x, y(bar.low));
    context.stroke();
    const top = y(Math.max(bar.open, bar.close));
    const height = Math.max(1, y(Math.min(bar.open, bar.close)) - top);
    context.fillRect(x - Math.max(1, width * 0.3), top, Math.max(2, width * 0.6), height);
  });
  drawLine(context, bars, "ema20", "#d6c07a", max, span);
  drawLine(context, bars, "ema50", "#8fb8c9", max, span);
}

function drawLine(context, bars, key, color, max, span) {
  context.strokeStyle = color;
  context.beginPath();
  let started = false;
  const width = context.canvas.width / bars.length;
  bars.forEach((bar, index) => {
    if (bar[key] === null) return;
    const x = index * width + width / 2;
    const y = 16 + ((max - bar[key]) / span) * (context.canvas.height - 32);
    if (!started) {
      context.moveTo(x, y);
      started = true;
    } else {
      context.lineTo(x, y);
    }
  });
  context.stroke();
}

async function renderDecisions() {
  const data = await getJson("/api/decisions", true);
  if (data.decisions.length === 0) {
    main.innerHTML = "<section class='card'><h2>No stored decisions</h2><p>Run /decision EURUSD from Telegram, or a token-protected local assessment. A missing decision is not a trade.</p></section>";
    return;
  }
  main.innerHTML = data.decisions.map((item) => `<article class="card">
    <h2>${escapeText(item.tradingLabel)}</h2>
    <p>${escapeText(stamp(item.createdAtMs))} · ${escapeText(item.symbol)} · trading ${escapeText(item.tradingClassification)} · research ${escapeText(item.researchClassification)} · ${item.actionable ? "actionable" : "not actionable"}</p>
    <p>${escapeText(item.researchLabel)}</p>
    <p>${escapeText(item.researchReason)}</p>
    <p>WAIT reasons: ${escapeText((item.waitReasons ?? []).join(" "))}</p>
    <p>Bullish evidence: ${escapeText((item.bullish ?? []).join(", ") || "none")}</p>
    <p>Bearish evidence: ${escapeText((item.bearish ?? []).join(", ") || "none")}</p>
    <p>Warnings: ${escapeText((item.warnings ?? []).join(", ") || "none")}</p>
    <p>AI ${escapeText(item.ai)}. ${escapeText(item.aiDetail ?? "")}</p>
    <p>Risk ${escapeText(item.risk)}. ${escapeText(item.riskDetail ?? "")}</p>
    <p>${escapeText(item.levels)}</p>
  </article>`).join("");
}

async function renderPerformance() {
  const data = await getJson("/api/performance");
  main.innerHTML = `<section class="card"><h2>Phase 8 historical simulations</h2><p>${escapeText(data.disclaimer)}</p><p class="muted">${data.rows.length} catalog rows. Window end is exclusive. These rows are not broker results.</p></section>` + table(
    data.rows.map((row) => [
      row.strategyId,
      row.timeframe,
      row.sample,
      utcDay(row.startMs),
      utcDay(row.endMs),
      String(row.trades),
      money(row.netProfit),
      row.profitFactorBeforeCosts === null ? "n/a" : row.profitFactorBeforeCosts.toFixed(3),
      row.profitFactorAfterCosts === null ? "n/a" : row.profitFactorAfterCosts.toFixed(3),
      money(row.maxDrawdown),
      money(row.executionCosts),
      `${row.assumptions.sizing}; spread ${row.assumptions.spreadPips}; slip ${row.assumptions.slippagePips}`,
    ]),
    ["Strategy", "Timeframe", "Sample", "Start", "End exclusive", "Trades", "Net", "PF before costs", "PF after costs", "Drawdown", "Costs", "Assumptions"],
  );
}

async function renderRisk() {
  const data = await getJson("/api/risk");
  main.innerHTML = `<section class="card"><h2>Risk limits</h2>
    <p>Max risk ${escapeText(data.maxRiskPercent)}%. Max lots ${escapeText(data.maxLots)}. Max user-reported daily loss ${escapeText(data.maxDailyLoss)}.</p>
    <p>${escapeText(data.killSwitch)}</p>
    <p>${escapeText(data.source)}</p>
    <p>Broker P/L: ${escapeText(data.brokerPnl)}. Account equity: ${escapeText(data.accountEquity)}. There is no account balance on this page.</p>
    <label>Chat id <input id="risk-chat" inputmode="numeric" autocomplete="off"></label>
    <button id="risk-load" type="button">Load user-reported status</button>
    <p id="risk-chat-result" class="muted"></p>
  </section>`;
  document.querySelector("#risk-load").addEventListener("click", async () => {
    const chatId = document.querySelector("#risk-chat").value.trim();
    const target = document.querySelector("#risk-chat-result");
    try {
      const status = await getJson(`/api/risk?chatId=${encodeURIComponent(chatId)}`, true);
      const chat = status.chat;
      target.textContent = chat
        ? `User-reported loss ${chat.totalLoss}. Kill switch ${chat.killed ? "ON" : "OFF"}. This is not broker P/L.`
        : "No user-reported status was returned.";
    } catch (error) {
      target.textContent = error.message;
    }
  });
}

async function renderSettings() {
  const data = await getJson("/api/settings");
  const preferred = localStorage.getItem("tradexa.timeframe") || "H1";
  main.innerHTML = `<section class="card"><h2>Local settings</h2>
    <p>Symbol ${escapeText(data.symbol)}. Data mode ${escapeText(data.dataMode)}.</p>
    <label>Preferred timeframe <select id="preferred">${data.timeframes.map((item) => `<option ${item === preferred ? "selected" : ""}>${item}</option>`).join("")}</select></label>
    <p>Live quote: ${escapeText(data.liveQuoteStatus)}. Configured feed: ${data.liveQuoteConfigured ? "yes" : "no"}.</p>
    <p>AI provider configured: ${data.aiConfigured ? "yes" : "no"}${data.aiModel ? `, model ${escapeText(data.aiModel)}` : ""}. A configured provider is not a passed assessment. Schema acceptance is shown only on a stored decision.</p>
    <label>Dashboard token <input id="dashboard-token" type="password" autocomplete="off" placeholder="Session only"></label>
    <p class="muted">The token is kept in sessionStorage for this tab only. It is not written to localStorage or the server. API keys are not shown. Real-money trading cannot be enabled here.</p>
  </section>`;
  const tokenInput = document.querySelector("#dashboard-token");
  tokenInput.value = sessionStorage.getItem("tradexa.dashboardToken") || "";
  tokenInput.addEventListener("change", () => {
    const value = tokenInput.value.trim();
    if (value.length === 0) {
      sessionStorage.removeItem("tradexa.dashboardToken");
    } else {
      sessionStorage.setItem("tradexa.dashboardToken", value);
    }
  });
  document.querySelector("#preferred").addEventListener("change", (event) => {
    localStorage.setItem("tradexa.timeframe", event.target.value);
  });
}

function num(value, digits) {
  return typeof value === "number" ? value.toFixed(digits) : "n/a";
}

function money(value) {
  return typeof value === "number" ? value.toFixed(2) : "n/a";
}

function utcDay(timeMs) {
  return new Date(timeMs).toISOString().slice(0, 16).replace("T", " ") + " UTC";
}

function zones(items) {
  if (!items || items.length === 0) return "none in the latest section";
  return items.slice(0, 2).map((item) => `${item.lower.toFixed(5)}–${item.upper.toFixed(5)}`).join(", ");
}

function quoteText(data) {
  const quote = data.liveQuote;
  const candle = data.lastCandleTimeMs === null ? "unavailable" : stamp(data.lastCandleTimeMs);
  const historical = `${data.dataMode}. ${data.dataSource} Last H1 candle open: ${candle}.`;
  if (!quote || quote.status !== "accepted") {
    return `${historical} Live quote: ${quote?.status ?? "unavailable"}. No bid or ask is shown.`;
  }
  return `${historical} Live ${quote.name} bid ${quote.bid} ask ${quote.ask} spread ${Number(quote.spreadPips).toFixed(1)} pips at ${stamp(quote.quotedAtMs)}.`;
}

function card(title, text) {
  return `<article class="card"><h2>${escapeText(title)}</h2><p>${escapeText(text)}</p></article>`;
}

function table(rows, headers) {
  if (rows.length === 0) return "<p class='muted'>No rows.</p>";
  return `<div class="table-wrap"><table><thead><tr>${headers.map((item) => `<th>${escapeText(item)}</th>`).join("")}</tr></thead><tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${escapeText(String(cell))}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
}

async function getJson(path, authenticated = false) {
  const headers = {};
  if (authenticated) {
    const token = sessionStorage.getItem("tradexa.dashboardToken");
    if (token) headers.authorization = `Bearer ${token}`;
  }
  const response = await fetch(path, { headers });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error ?? "Request failed");
  return body;
}

function stamp(timeMs) {
  return new Date(timeMs).toISOString().replace(".000Z", " UTC").replace("T", " ");
}

function escapeText(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
}
