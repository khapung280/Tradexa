import { findSession, type TradingSession } from "./sessions.js";

export type SessionMarketState = "open" | "closed";

export type SessionDetection =
  | {
      ok: true;
      session: TradingSession;
      state: SessionMarketState;
      localWeekday: string;
      localTime: string;
    }
  | {
      ok: false;
      error: "unknown_session";
    };

const WEEKDAYS = new Set(["Mon", "Tue", "Wed", "Thu", "Fri"]);

type LocalClock = {
  weekday: string;
  hour: number;
  minute: number;
  second: number;
};

function parseClockToSeconds(hhmm: string): number {
  const [hoursText = "0", minutesText = "0"] = hhmm.split(":");
  return Number(hoursText) * 3600 + Number(minutesText) * 60;
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

function localClock(nowMs: number, timeZone: string): LocalClock | undefined {
  if (!Number.isFinite(nowMs)) {
    return undefined;
  }

  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    weekday: "short",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date(nowMs));

  const read = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? "";

  let hour = Number(read("hour"));
  if (hour === 24) {
    hour = 0;
  }

  const minute = Number(read("minute"));
  const second = Number(read("second"));
  const weekday = read("weekday");

  if (!Number.isFinite(hour) || !Number.isFinite(minute) || !Number.isFinite(second) || weekday === "") {
    return undefined;
  }

  return { weekday, hour, minute, second };
}

export function detectSession(raw: string, nowMs: number): SessionDetection {
  const session = findSession(raw);
  if (session === undefined) {
    return { ok: false, error: "unknown_session" };
  }

  const clock = localClock(nowMs, session.timezone);
  if (clock === undefined) {
    return {
      ok: true,
      session,
      state: "closed",
      localWeekday: "",
      localTime: "",
    };
  }

  const localSeconds = clock.hour * 3600 + clock.minute * 60 + clock.second;
  const openSeconds = parseClockToSeconds(session.localOpen);
  const closeSeconds = parseClockToSeconds(session.localClose);
  const weekdayOpen = WEEKDAYS.has(clock.weekday);
  const withinHours = localSeconds >= openSeconds && localSeconds < closeSeconds;

  return {
    ok: true,
    session,
    state: weekdayOpen && withinHours ? "open" : "closed",
    localWeekday: clock.weekday,
    localTime: `${pad(clock.hour)}:${pad(clock.minute)}:${pad(clock.second)}`,
  };
}
