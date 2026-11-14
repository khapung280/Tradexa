import { describe, expect, it } from "vitest";
import { calculateRisk } from "../src/risk/calculate.ts";
import {
  FOREX_PAIRS,
  JPY_PIP_SIZE,
  STANDARD_LOT_UNITS,
  STANDARD_PIP_SIZE,
  findPair,
} from "../src/risk/pairs.ts";
import type { RiskRequest } from "../src/risk/types.ts";

function request(overrides: Partial<RiskRequest> = {}): RiskRequest {
  return {
    accountBalance: 10_000,
    riskPercent: 1,
    pair: "EURUSD",
    stopLossPips: 50,
    ...overrides,
  };
}

describe("FOREX_PAIRS", () => {
  it("uses standard pip size except for JPY quotes", () => {
    for (const pair of FOREX_PAIRS) {
      expect(pair.unitsPerLot).toBe(STANDARD_LOT_UNITS);
      if (pair.quote === "JPY") {
        expect(pair.pipSize).toBe(JPY_PIP_SIZE);
      } else {
        expect(pair.pipSize).toBe(STANDARD_PIP_SIZE);
      }
    }
  });
});

describe("calculateRisk", () => {
  it("sizes EURUSD at 0.20 lots for a USD account without a conversion rate", () => {
    const result = calculateRisk(
      request({
        accountBalance: 10_000,
        riskPercent: 1,
        pair: "EURUSD",
        stopLossPips: 50,
        accountCurrency: "USD",
      }),
    );

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.pair).toBe("EURUSD");
    expect(result.base).toBe("EUR");
    expect(result.quote).toBe("USD");
    expect(result.pipSize).toBe(STANDARD_PIP_SIZE);
    expect(result.unitsPerLot).toBe(STANDARD_LOT_UNITS);
    expect(result.riskAmount).toBe(100);
    expect(result.pipValueQuotePerLot).toBe(10);
    expect(result.pipValueAccountPerLot).toBe(10);
    expect(result.pipValueCurrency).toBe("USD");
    expect(result.quoteToAccountRate).toBe(1);
    expect(result.conversion).toBe("not_required");
    expect(result.accountCurrency).toBe("USD");
    expect(result.positionSizeLots).toBe(0.2);
  });

  it("ignores a supplied rate when account currency already matches the quote", () => {
    const result = calculateRisk(
      request({
        accountCurrency: "USD",
        quoteToAccountRate: 999,
      }),
    );

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.quoteToAccountRate).toBe(1);
    expect(result.pipValueAccountPerLot).toBe(10);
    expect(result.positionSizeLots).toBe(0.2);
  });

  it("requires an explicit JPY-to-USD rate for USDJPY on a USD account", () => {
    const missing = calculateRisk(
      request({
        pair: "USDJPY",
        accountCurrency: "USD",
      }),
    );
    expect(missing).toEqual({ ok: false, error: "conversion_required" });
    expect(missing).not.toHaveProperty("positionSizeLots");

    const result = calculateRisk(
      request({
        pair: "USDJPY",
        accountCurrency: "USD",
        quoteToAccountRate: 0.01,
      }),
    );

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.pipSize).toBe(0.01);
    expect(result.quote).toBe("JPY");
    expect(result.pipValueQuotePerLot).toBe(1_000);
    expect(result.pipValueCurrency).toBe("JPY");
    expect(result.quoteToAccountRate).toBe(0.01);
    expect(result.pipValueAccountPerLot).toBe(10);
    expect(result.accountCurrency).toBe("USD");
    expect(result.conversion).toBe("required");
    expect(result.riskAmount).toBe(100);
    expect(result.positionSizeLots).toBe(0.2);
  });

  it("requires an explicit GBP-to-USD rate for EURGBP on a USD account", () => {
    const missing = calculateRisk(
      request({
        pair: "EURGBP",
        accountCurrency: "USD",
      }),
    );
    expect(missing).toEqual({ ok: false, error: "conversion_required" });
    expect(missing).not.toHaveProperty("positionSizeLots");

    const result = calculateRisk(
      request({
        pair: "EURGBP",
        accountCurrency: "USD",
        quoteToAccountRate: 1.25,
      }),
    );

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.quote).toBe("GBP");
    expect(result.pipValueQuotePerLot).toBe(10);
    expect(result.pipValueCurrency).toBe("GBP");
    expect(result.quoteToAccountRate).toBe(1.25);
    expect(result.pipValueAccountPerLot).toBe(12.5);
    expect(result.conversion).toBe("required");
    expect(result.positionSizeLots).toBe(0.16);
  });

  it("does not size a position when account currency is missing", () => {
    const result = calculateRisk(request({ pair: "EURUSD" }));
    expect(result).toEqual({ ok: false, error: "conversion_required" });
    expect(result).not.toHaveProperty("positionSizeLots");
  });

  it("rejects missing, zero, negative, and non-finite conversion rates", () => {
    const usdJpy = { pair: "USDJPY", accountCurrency: "USD" } as const;
    expect(calculateRisk(request(usdJpy))).toEqual({
      ok: false,
      error: "conversion_required",
    });
    expect(calculateRisk(request({ ...usdJpy, quoteToAccountRate: 0 }))).toEqual({
      ok: false,
      error: "invalid_conversion_rate",
    });
    expect(calculateRisk(request({ ...usdJpy, quoteToAccountRate: -1 }))).toEqual({
      ok: false,
      error: "invalid_conversion_rate",
    });
    expect(calculateRisk(request({ ...usdJpy, quoteToAccountRate: Number.NaN }))).toEqual({
      ok: false,
      error: "invalid_conversion_rate",
    });
    expect(
      calculateRisk(request({ ...usdJpy, quoteToAccountRate: Number.POSITIVE_INFINITY })),
    ).toEqual({
      ok: false,
      error: "invalid_conversion_rate",
    });
  });

  it("rejects non-positive or non-finite inputs", () => {
    expect(calculateRisk(request({ accountBalance: 0 }))).toEqual({
      ok: false,
      error: "invalid_balance",
    });
    expect(calculateRisk(request({ accountBalance: -1 }))).toEqual({
      ok: false,
      error: "invalid_balance",
    });
    expect(calculateRisk(request({ accountBalance: Number.NaN }))).toEqual({
      ok: false,
      error: "invalid_balance",
    });
    expect(calculateRisk(request({ riskPercent: 0 }))).toEqual({
      ok: false,
      error: "invalid_risk_percent",
    });
    expect(calculateRisk(request({ riskPercent: -2 }))).toEqual({
      ok: false,
      error: "invalid_risk_percent",
    });
    expect(calculateRisk(request({ stopLossPips: 0 }))).toEqual({
      ok: false,
      error: "invalid_stop_loss_pips",
    });
    expect(calculateRisk(request({ stopLossPips: Number.POSITIVE_INFINITY }))).toEqual({
      ok: false,
      error: "invalid_stop_loss_pips",
    });
  });

  it("rejects unknown pairs", () => {
    expect(calculateRisk(request({ pair: "FAKEUSD" }))).toEqual({
      ok: false,
      error: "unknown_pair",
    });
    expect(calculateRisk(request({ pair: "" }))).toEqual({
      ok: false,
      error: "unknown_pair",
    });
    expect(findPair("EURUSD")?.symbol).toBe("EURUSD");
    expect(findPair("eur/usd")?.symbol).toBe("EURUSD");
    expect(findPair("FAKEUSD")).toBeUndefined();
  });
});

describe("calculateRisk limits", () => {
  const limits = { maxRiskPercent: 2, maxLots: 2, maxDailyLoss: 100 };

  it("allows a risk percentage at or below the configured maximum", () => {
    const atLimit = calculateRisk(
      request({ riskPercent: 2, accountCurrency: "USD" }),
      limits,
    );
    const below = calculateRisk(
      request({ riskPercent: 1, accountCurrency: "USD" }),
      limits,
    );
    expect(atLimit.ok).toBe(true);
    expect(below.ok).toBe(true);
    if (atLimit.ok) {
      expect(atLimit.positionSizeLots).toBe(0.4);
    }
  });

  it("rejects a risk percentage above the maximum without a lot size", () => {
    const result = calculateRisk(
      request({ riskPercent: 3, accountCurrency: "USD" }),
      limits,
    );
    expect(result).toEqual({ ok: false, error: "risk_percent_limit" });
    expect(result).not.toHaveProperty("positionSizeLots");
  });

  it("allows a lot size at the configured maximum", () => {
    const result = calculateRisk(request({ accountCurrency: "USD" }), {
      maxRiskPercent: 2,
      maxLots: 0.2,
      maxDailyLoss: 100,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.positionSizeLots).toBe(0.2);
  });

  it("rejects a lot size above the maximum without returning the size", () => {
    const result = calculateRisk(request({ accountCurrency: "USD" }), {
      maxRiskPercent: 2,
      maxLots: 0.1,
      maxDailyLoss: 100,
    });
    expect(result).toEqual({ ok: false, error: "lot_size_limit" });
    expect(result).not.toHaveProperty("positionSizeLots");
  });
});
