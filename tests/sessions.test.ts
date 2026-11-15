import { describe, expect, it } from "vitest";
import {
  SESSION_IDS,
  TRADING_SESSIONS,
  findSession,
  isSessionId,
  normalizeSessionId,
} from "../src/market/sessions.ts";

const EXPECTED = [
  {
    id: "sydney",
    displayName: "Sydney",
    timezone: "Australia/Sydney",
    localOpen: "08:00",
    localClose: "17:00",
  },
  {
    id: "tokyo",
    displayName: "Tokyo (Asian session)",
    timezone: "Asia/Tokyo",
    localOpen: "09:00",
    localClose: "18:00",
  },
  {
    id: "london",
    displayName: "London",
    timezone: "Europe/London",
    localOpen: "08:00",
    localClose: "17:00",
  },
  {
    id: "newyork",
    displayName: "New York",
    timezone: "America/New_York",
    localOpen: "08:00",
    localClose: "17:00",
  },
] as const;

describe("TRADING_SESSIONS", () => {
  it("contains exactly the four Forex trading sessions", () => {
    expect(SESSION_IDS).toEqual(["sydney", "tokyo", "london", "newyork"]);
    expect(TRADING_SESSIONS).toHaveLength(4);
    expect(TRADING_SESSIONS).toEqual([...EXPECTED]);
  });

  it("uses the required IANA timezones", () => {
    expect(findSession("sydney")?.timezone).toBe("Australia/Sydney");
    expect(findSession("tokyo")?.timezone).toBe("Asia/Tokyo");
    expect(findSession("london")?.timezone).toBe("Europe/London");
    expect(findSession("newyork")?.timezone).toBe("America/New_York");
  });

  it("looks up every catalog id", () => {
    for (const expected of EXPECTED) {
      expect(findSession(expected.id)).toEqual(expected);
      expect(findSession(expected.id.toUpperCase())).toEqual(expected);
      expect(isSessionId(expected.id)).toBe(true);
    }
  });

  it("accepts trimmed mixed-case ids", () => {
    expect(normalizeSessionId(" Tokyo ")).toBe("tokyo");
    expect(findSession(" LONDON ")).toEqual({
      id: "london",
      displayName: "London",
      timezone: "Europe/London",
      localOpen: "08:00",
      localClose: "17:00",
    });
  });

  it("rejects unsupported or invalid session ids", () => {
    const invalid = [
      "",
      "   ",
      "frankfurt",
      "chicago",
      "asian",
      "new york",
      "new_york",
      "sydneytokyo",
      "utc",
    ];
    for (const value of invalid) {
      expect(findSession(value), value).toBeUndefined();
      expect(isSessionId(value), value).toBe(false);
    }
  });
});
