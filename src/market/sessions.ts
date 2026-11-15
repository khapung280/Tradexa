export const SESSION_IDS = ["sydney", "tokyo", "london", "newyork"] as const;
export type SessionId = (typeof SESSION_IDS)[number];

export type TradingSession = {
  id: SessionId;
  displayName: string;
  timezone: string;
  localOpen: string;
  localClose: string;
};

export const TRADING_SESSIONS: readonly TradingSession[] = [
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
];

const SESSIONS_BY_ID = new Map<string, TradingSession>(
  TRADING_SESSIONS.map((session) => [session.id, session]),
);

export function normalizeSessionId(raw: string): string {
  return raw.trim().toLowerCase();
}

export function findSession(raw: string): TradingSession | undefined {
  const id = normalizeSessionId(raw);
  if (id.length === 0) {
    return undefined;
  }
  return SESSIONS_BY_ID.get(id);
}

export function isSessionId(value: string): value is SessionId {
  return findSession(value) !== undefined;
}
