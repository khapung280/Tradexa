import { describe, expect, it } from "vitest";
import { detectSession } from "../src/market/sessionState.ts";

function utc(iso: string): number {
  const ms = Date.parse(iso);
  if (!Number.isFinite(ms)) {
    throw new Error(`invalid utc timestamp: ${iso}`);
  }
  return ms;
}

describe("detectSession", () => {
  it("rejects invalid session ids", () => {
    const now = utc("2026-01-12T12:00:00.000Z");
    for (const value of ["", "   ", "frankfurt", "chicago", "asian", "new york", "utc"]) {
      expect(detectSession(value, now), value).toEqual({
        ok: false,
        error: "unknown_session",
      });
    }
  });

  it("opens Sydney at 08:00 and closes at 17:00 local time on weekdays", () => {
    const open = detectSession("sydney", utc("2026-01-11T21:00:00.000Z"));
    expect(open).toMatchObject({
      ok: true,
      state: "open",
      localWeekday: "Mon",
      localTime: "08:00:00",
    });

    const before = detectSession("sydney", utc("2026-01-11T20:59:59.000Z"));
    expect(before).toMatchObject({
      ok: true,
      state: "closed",
      localWeekday: "Mon",
      localTime: "07:59:59",
    });

    const lastSecond = detectSession("sydney", utc("2026-01-12T05:59:59.000Z"));
    expect(lastSecond).toMatchObject({
      ok: true,
      state: "open",
      localWeekday: "Mon",
      localTime: "16:59:59",
    });

    const close = detectSession("sydney", utc("2026-01-12T06:00:00.000Z"));
    expect(close).toMatchObject({
      ok: true,
      state: "closed",
      localWeekday: "Mon",
      localTime: "17:00:00",
    });
  });

  it("opens Tokyo at 09:00 and closes at 18:00 local time on weekdays", () => {
    expect(detectSession("tokyo", utc("2026-01-12T00:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localWeekday: "Mon",
      localTime: "09:00:00",
    });
    expect(detectSession("TOKYO", utc("2026-01-11T23:59:59.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localTime: "08:59:59",
    });
    expect(detectSession("tokyo", utc("2026-01-12T08:59:59.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "17:59:59",
    });
    expect(detectSession("tokyo", utc("2026-01-12T09:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localTime: "18:00:00",
    });
  });

  it("opens London at 08:00 and closes at 17:00 local time on weekdays", () => {
    expect(detectSession("london", utc("2026-01-12T08:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localWeekday: "Mon",
      localTime: "08:00:00",
    });
    expect(detectSession("london", utc("2026-01-12T07:59:59.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localTime: "07:59:59",
    });
    expect(detectSession("london", utc("2026-01-12T16:59:59.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "16:59:59",
    });
    expect(detectSession("london", utc("2026-01-12T17:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localTime: "17:00:00",
    });
  });

  it("opens New York at 08:00 and closes at 17:00 local time on weekdays", () => {
    expect(detectSession("newyork", utc("2026-01-12T13:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localWeekday: "Mon",
      localTime: "08:00:00",
    });
    expect(detectSession("newyork", utc("2026-01-12T12:59:59.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localTime: "07:59:59",
    });
    expect(detectSession("newyork", utc("2026-01-12T21:59:59.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "16:59:59",
    });
    expect(detectSession("newyork", utc("2026-01-12T22:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localTime: "17:00:00",
    });
  });

  it("is open Monday through Friday in the session's local timezone", () => {
    const londonWeek = [
      ["2026-01-12T12:00:00.000Z", "Mon", "open"],
      ["2026-01-13T12:00:00.000Z", "Tue", "open"],
      ["2026-01-14T12:00:00.000Z", "Wed", "open"],
      ["2026-01-15T12:00:00.000Z", "Thu", "open"],
      ["2026-01-16T12:00:00.000Z", "Fri", "open"],
    ] as const;

    for (const [iso, weekday, state] of londonWeek) {
      expect(detectSession("london", utc(iso)), iso).toMatchObject({
        ok: true,
        state,
        localWeekday: weekday,
      });
    }
  });

  it("is closed on local Saturday and Sunday even during session hours", () => {
    expect(detectSession("london", utc("2026-01-17T12:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localWeekday: "Sat",
      localTime: "12:00:00",
    });
    expect(detectSession("london", utc("2026-01-18T12:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localWeekday: "Sun",
      localTime: "12:00:00",
    });
    expect(detectSession("tokyo", utc("2026-01-17T03:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localWeekday: "Sat",
      localTime: "12:00:00",
    });
  });

  it("uses the session local weekday, not UTC", () => {
    expect(detectSession("sydney", utc("2026-01-16T21:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localWeekday: "Sat",
      localTime: "08:00:00",
    });
    expect(detectSession("sydney", utc("2026-01-18T21:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localWeekday: "Mon",
      localTime: "08:00:00",
    });
  });

  it("applies London daylight saving time automatically", () => {
    const winterUtc = utc("2026-01-12T16:30:00.000Z");
    expect(detectSession("london", winterUtc)).toMatchObject({
      ok: true,
      state: "open",
      localWeekday: "Mon",
      localTime: "16:30:00",
    });

    const summerUtc = utc("2026-07-13T16:30:00.000Z");
    expect(detectSession("london", summerUtc)).toMatchObject({
      ok: true,
      state: "closed",
      localWeekday: "Mon",
      localTime: "17:30:00",
    });

    expect(detectSession("london", utc("2026-07-13T07:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "08:00:00",
    });
  });

  it("applies New York daylight saving time automatically", () => {
    expect(detectSession("newyork", utc("2026-01-12T12:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localTime: "07:00:00",
    });
    expect(detectSession("newyork", utc("2026-07-13T12:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "08:00:00",
    });
  });

  it("applies Sydney daylight saving time automatically", () => {
    expect(detectSession("sydney", utc("2026-01-11T21:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "08:00:00",
    });
    expect(detectSession("sydney", utc("2026-07-12T21:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "closed",
      localWeekday: "Mon",
      localTime: "07:00:00",
    });
    expect(detectSession("sydney", utc("2026-07-12T22:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "08:00:00",
    });
  });

  it("does not apply DST in Tokyo", () => {
    expect(detectSession("tokyo", utc("2026-01-12T00:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "09:00:00",
    });
    expect(detectSession("tokyo", utc("2026-07-13T00:00:00.000Z"))).toMatchObject({
      ok: true,
      state: "open",
      localTime: "09:00:00",
    });
  });
});
