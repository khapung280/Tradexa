import { detectSession, type SessionDetection } from "./sessionState.js";

export type SessionOverlapSuccess = Extract<SessionDetection, { ok: true }>;

export type SessionOverlap =
  | {
      ok: true;
      overlapping: boolean;
      first: SessionOverlapSuccess;
      second: SessionOverlapSuccess;
    }
  | {
      ok: false;
      error: "unknown_session";
    };

export function detectSessionOverlap(
  firstRaw: string,
  secondRaw: string,
  nowMs: number,
): SessionOverlap {
  const first = detectSession(firstRaw, nowMs);
  const second = detectSession(secondRaw, nowMs);
  if (!first.ok || !second.ok) {
    return { ok: false, error: "unknown_session" };
  }

  return {
    ok: true,
    overlapping: first.state === "open" && second.state === "open",
    first,
    second,
  };
}
