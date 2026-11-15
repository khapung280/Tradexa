import { describe, expect, it } from "vitest";
import { detectSessionOverlap } from "../src/market/sessionOverlap.ts";

function utc(iso: string): number {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) {
    throw new Error(`invalid utc timestamp: ${iso}`);
  }
  return ms;
}

function overlap(
  first: string,
  second: string,
  iso: string,
): boolean | "unknown_session" {
  const result = detectSessionOverlap(first, second, utc(iso));
  return result.ok ? result.overlapping : result.error;
}

describe("detectSessionOverlap", () => {
  it("rejects invalid session ids", () => {
    const now = utc("2026-01-12T14:00:00.000Z");
    expect(detectSessionOverlap("frankfurt", "london", now)).toEqual({
      ok: false,
      error: "unknown_session",
    });
    expect(detectSessionOverlap("london", "chicago", now)).toEqual({
      ok: false,
      error: "unknown_session",
    });
    expect(detectSessionOverlap("", "newyork", now)).toEqual({
      ok: false,
      error: "unknown_session",
    });
  });

  it("reports London/New York overlap in winter (GMT and EST)", () => {
    expect(overlap("london", "newyork", "2026-01-12T12:59:59.000Z")).toBe(false);
    expect(overlap("london", "newyork", "2026-01-12T13:00:00.000Z")).toBe(true);
    expect(overlap("newyork", "london", "2026-01-12T16:59:59.000Z")).toBe(true);
    expect(overlap("london", "newyork", "2026-01-12T17:00:00.000Z")).toBe(false);
  });

  it("reports London/New York overlap in summer (BST and EDT)", () => {
    expect(overlap("london", "newyork", "2026-07-13T11:59:59.000Z")).toBe(false);
    expect(overlap("london", "newyork", "2026-07-13T12:00:00.000Z")).toBe(true);
    expect(overlap("london", "newyork", "2026-07-13T15:59:59.000Z")).toBe(true);
    expect(overlap("london", "newyork", "2026-07-13T16:00:00.000Z")).toBe(false);
  });

  it("handles the spring DST mismatch when New York springs forward before London", () => {
    // 2026-03-09: America/New_York on EDT, Europe/London still GMT.
    expect(overlap("london", "newyork", "2026-03-09T11:59:59.000Z")).toBe(false);
    expect(overlap("london", "newyork", "2026-03-09T12:00:00.000Z")).toBe(true);
    expect(overlap("london", "newyork", "2026-03-09T16:59:59.000Z")).toBe(true);
    expect(overlap("london", "newyork", "2026-03-09T17:00:00.000Z")).toBe(false);
  });

  it("handles the autumn DST mismatch when London falls back before New York", () => {
    // 2026-10-26: Europe/London on GMT, America/New_York still EDT.
    expect(overlap("london", "newyork", "2026-10-26T11:59:59.000Z")).toBe(false);
    expect(overlap("london", "newyork", "2026-10-26T12:00:00.000Z")).toBe(true);
    expect(overlap("london", "newyork", "2026-10-26T16:59:59.000Z")).toBe(true);
    expect(overlap("london", "newyork", "2026-10-26T17:00:00.000Z")).toBe(false);
  });

  it("does not treat Tokyo and New York as overlapping", () => {
    const samples = [
      "2026-01-12T00:00:00.000Z",
      "2026-01-12T08:59:59.000Z",
      "2026-01-12T09:00:00.000Z",
      "2026-01-12T13:00:00.000Z",
      "2026-07-13T00:00:00.000Z",
      "2026-07-13T12:00:00.000Z",
      "2026-07-13T16:00:00.000Z",
    ];
    for (const iso of samples) {
      expect(overlap("tokyo", "newyork", iso), iso).toBe(false);
    }
  });

  it("does not treat Sydney and London as overlapping", () => {
    const samples = [
      "2026-01-12T05:59:59.000Z",
      "2026-01-12T08:00:00.000Z",
      "2026-07-13T06:59:59.000Z",
      "2026-07-13T08:00:00.000Z",
    ];
    for (const iso of samples) {
      expect(overlap("sydney", "london", iso), iso).toBe(false);
    }
  });

  it("detects Tokyo/London overlap near Tokyo close", () => {
    expect(overlap("tokyo", "london", "2026-01-12T07:59:59.000Z")).toBe(false);
    expect(overlap("tokyo", "london", "2026-01-12T08:00:00.000Z")).toBe(true);
    expect(overlap("tokyo", "london", "2026-01-12T08:59:59.000Z")).toBe(true);
    expect(overlap("tokyo", "london", "2026-01-12T09:00:00.000Z")).toBe(false);
  });

  it("detects Sydney/Tokyo overlap during Asian hours", () => {
    expect(overlap("sydney", "tokyo", "2026-01-11T23:59:59.000Z")).toBe(false);
    expect(overlap("sydney", "tokyo", "2026-01-12T00:00:00.000Z")).toBe(true);
    expect(overlap("sydney", "tokyo", "2026-01-12T05:59:59.000Z")).toBe(true);
    expect(overlap("sydney", "tokyo", "2026-01-12T06:00:00.000Z")).toBe(false);
  });

  it("is not overlapping on weekends even during weekday session hours", () => {
    expect(overlap("london", "newyork", "2026-01-17T14:00:00.000Z")).toBe(false);
    expect(overlap("london", "newyork", "2026-01-18T14:00:00.000Z")).toBe(false);
  });

  it("includes the open sessions and local clocks when overlapping", () => {
    const result = detectSessionOverlap(
      "london",
      "newyork",
      utc("2026-01-12T14:00:00.000Z"),
    );
    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.overlapping).toBe(true);
    expect(result.first).toMatchObject({
      ok: true,
      state: "open",
      localTime: "14:00:00",
    });
    expect(result.second).toMatchObject({
      ok: true,
      state: "open",
      localTime: "09:00:00",
    });
    expect(result.first.session.id).toBe("london");
    expect(result.second.session.id).toBe("newyork");
  });
});
