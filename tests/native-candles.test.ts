import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import {
  formatUtcTimestamp,
  parseNativeCsv,
  parseNativeTimestamp,
  readClosedNativeCandles,
} from "../src/market/nativeCandles.ts";

const roots: string[] = [];

afterEach(() => {
  for (const root of roots.splice(0)) {
    rmSync(root, { recursive: true, force: true });
  }
});

function tempRoot(): string {
  const root = mkdtempSync(join(tmpdir(), "tradexa-candles-"));
  roots.push(root);
  return root;
}

function csv(rows: readonly string[]): string {
  return ["time_utc,open,high,low,close,volume", ...rows].join("\n") + "\n";
}

describe("native CSV parsing", () => {
  it("reads a UTC timestamp and keeps OHLC in order", () => {
    const candles = parseNativeCsv(
      csv(["2024-01-02 00:05:00,1.10000,1.10020,1.09980,1.10010,10"]),
      5 * 60_000,
    );
    expect(candles).toHaveLength(1);
    expect(candles[0]?.timeMs).toBe(Date.UTC(2024, 0, 2, 0, 5, 0));
    expect(formatUtcTimestamp(candles[0]?.timeMs ?? 0)).toBe("2024-01-02 00:05:00 UTC");
    expect(candles[0]).toMatchObject({ open: 1.1, high: 1.1002, low: 1.0998, close: 1.1001, volume: 10 });
  });

  it("accepts Java Double.toString scientific volume without changing the file", () => {
    const text = csv(["2024-01-02 00:00:00,1.20000,1.20010,1.19990,1.20000,1.0E-4"]);
    const candles = parseNativeCsv(text, 24 * 60 * 60_000);
    expect(candles[0]?.volume).toBeCloseTo(0.0001, 10);
  });

  it("rejects a duplicate timestamp, an out-of-order row, and invalid OHLC", () => {
    const duration = 60 * 60_000;
    expect(() =>
      parseNativeCsv(
        csv([
          "2024-01-02 00:00:00,1.1,1.2,1.0,1.1,1",
          "2024-01-02 00:00:00,1.1,1.2,1.0,1.1,1",
        ]),
        duration,
      ),
    ).toThrow(/duplicate/i);
    expect(() =>
      parseNativeCsv(
        csv([
          "2024-01-02 02:00:00,1.1,1.2,1.0,1.1,1",
          "2024-01-02 01:00:00,1.1,1.2,1.0,1.1,1",
        ]),
        duration,
      ),
    ).toThrow(/out of order/i);
    expect(() =>
      parseNativeCsv(csv(["2024-01-02 00:00:00,1.2,1.1,1.0,1.1,1"]), duration),
    ).toThrow(/OHLC/);
  });

  it("rejects a timestamp that is not on the timeframe grid", () => {
    expect(parseNativeTimestamp("2024-02-31 00:00:00")).toBeNull();
    expect(() =>
      parseNativeCsv(csv(["2024-01-02 00:01:00,1.1,1.2,1.0,1.1,1"]), 5 * 60_000),
    ).toThrow(/timestamp/);
  });
});

describe("readClosedNativeCandles", () => {
  it("reports a Friday-to-Monday hole as unclassified and does not invent candles", () => {
    const root = tempRoot();
    mkdirSync(join(root, "M5"));
    const text = csv([
      "2024-01-05 23:55:00,1.10000,1.10020,1.09980,1.10010,0",
      "2024-01-08 00:00:00,1.10010,1.10030,1.09990,1.10020,0",
    ]);
    const path = join(root, "M5", "2024-01-08.csv");
    writeFileSync(path, text);
    const before = readFileSync(path);
    const read = readClosedNativeCandles("M5", {
      root,
      lookbackBars: 50,
      asOfMs: Date.UTC(2024, 0, 8, 0, 5, 0),
    });
    expect(read.status).toBe("ok");
    if (read.status === "ok") {
      expect(read.candles).toHaveLength(2);
      expect(read.gaps[0]?.classification).toBe("UNCLASSIFIED_GAP");
      expect(read.gaps[0]?.missingCandles).toBe(576);
      expect(read.warnings[0]).toMatch(/not forward-filled/);
      expect(read.warnings[0]).toMatch(/UNCLASSIFIED_GAP/);
    }
    expect(readFileSync(path).equals(before)).toBe(true);
  });

  it("keeps files in date order and rejects a later file that steps backward", () => {
    const root = tempRoot();
    mkdirSync(join(root, "H1"));
    writeFileSync(
      join(root, "H1", "2024-01-02.csv"),
      csv(["2024-01-02 00:00:00,1.1,1.2,1.0,1.1,1"]),
    );
    writeFileSync(
      join(root, "H1", "2024-01-03.csv"),
      csv(["2024-01-01 00:00:00,1.1,1.2,1.0,1.1,1"]),
    );
    const read = readClosedNativeCandles("H1", {
      root,
      lookbackBars: 10,
      asOfMs: Date.UTC(2024, 0, 4),
    });
    expect(read.status).toBe("unavailable");
    if (read.status === "unavailable") {
      expect(read.reason).toMatch(/ascending/);
    }
  });

  it("excludes a candle that has not closed and ignores a non-date file", () => {
    const root = tempRoot();
    mkdirSync(join(root, "M15"));
    writeFileSync(join(root, "M15", "notes.csv"), "do not read\n");
    writeFileSync(
      join(root, "M15", "2024-01-02.csv"),
      csv([
        "2024-01-02 00:00:00,1.10000,1.10020,1.09980,1.10010,1",
        "2024-01-02 00:15:00,1.20000,1.20020,1.19980,1.20010,1",
      ]),
    );
    const read = readClosedNativeCandles("M15", {
      root,
      lookbackBars: 10,
      asOfMs: Date.UTC(2024, 0, 2, 0, 20, 0),
    });
    expect(read.status).toBe("ok");
    if (read.status === "ok") {
      expect(read.candles.map((candle) => candle.close)).toEqual([1.1001]);
      expect(read.filesRead).toBe(1);
    }
  });

  it("returns a clear unavailable result when the timeframe directory is missing", () => {
    const read = readClosedNativeCandles("D1", {
      root: tempRoot(),
      lookbackBars: 10,
      asOfMs: Date.UTC(2024, 0, 2),
    });
    expect(read).toMatchObject({
      status: "unavailable",
      reason: "D1 dataset is not available.",
    });
  });

  it("does not replace an invalid file with a guessed candle", () => {
    const root = tempRoot();
    mkdirSync(join(root, "D1"));
    const path = join(root, "D1", "2024-01-02.csv");
    const text = csv(["2024-01-02 00:00:00,1.2,1.0,0.9,1.1,1"]);
    writeFileSync(path, text);
    const read = readClosedNativeCandles("D1", {
      root,
      lookbackBars: 5,
      asOfMs: Date.UTC(2024, 0, 3),
    });
    expect(read.status).toBe("unavailable");
    expect(readFileSync(path, "utf8")).toBe(text);
  });

  it("selects D1 files for 15 January 2024 and does not open a later file", () => {
    const root = tempRoot();
    mkdirSync(join(root, "D1"));
    for (const day of [10, 11, 12, 13, 14, 15]) {
      const stamp = `2024-01-${String(day).padStart(2, "0")}`;
      writeFileSync(
        join(root, "D1", `${stamp}.csv`),
        csv([`${stamp} 00:00:00,1.10000,1.10020,1.09980,1.${10000 + day},1`]),
      );
    }
    writeFileSync(join(root, "D1", "2026-11-16.csv"), "this later file must not be parsed\n");
    const read = readClosedNativeCandles("D1", {
      root,
      lookbackBars: 3,
      asOfMs: Date.UTC(2024, 0, 15),
    });
    expect(read.status).toBe("ok");
    if (read.status === "ok") {
      expect(read.filesRead).toBe(4);
      expect(read.candles.map((candle) => candle.timeMs)).toEqual([
        Date.UTC(2024, 0, 12),
        Date.UTC(2024, 0, 13),
        Date.UTC(2024, 0, 14),
      ]);
    }
  });

  it("selects a historical M5 window and excludes a future date file", () => {
    const root = tempRoot();
    mkdirSync(join(root, "M5"));
    writeFileSync(
      join(root, "M5", "2024-03-01.csv"),
      csv([
        "2024-03-01 12:00:00,1.10000,1.10020,1.09980,1.10010,1",
        "2024-03-01 12:05:00,1.10010,1.10030,1.09990,1.10020,1",
        "2024-03-01 12:10:00,1.10020,1.10040,1.10000,1.10030,1",
      ]),
    );
    writeFileSync(join(root, "M5", "2024-03-02.csv"), "future file\n");
    const read = readClosedNativeCandles("M5", {
      root,
      lookbackBars: 10,
      asOfMs: Date.UTC(2024, 2, 1, 12, 12, 0),
    });
    expect(read.status).toBe("ok");
    if (read.status === "ok") {
      expect(read.filesRead).toBe(1);
      expect(read.candles.map((candle) => candle.close)).toEqual([1.1001, 1.1002]);
    }
  });

  it("keeps only candles that had closed by the middle of a CSV day", () => {
    const root = tempRoot();
    mkdirSync(join(root, "M5"));
    writeFileSync(
      join(root, "M5", "2024-06-03.csv"),
      csv([
        "2024-06-03 10:00:00,1.20000,1.20020,1.19980,1.20010,1",
        "2024-06-03 10:05:00,1.30000,1.30020,1.29980,1.30010,1",
      ]),
    );
    const read = readClosedNativeCandles("M5", {
      root,
      lookbackBars: 5,
      asOfMs: Date.UTC(2024, 5, 3, 10, 7, 0),
    });
    expect(read.status).toBe("ok");
    if (read.status === "ok") {
      expect(read.candles).toHaveLength(1);
      expect(read.candles[0]?.close).toBeCloseTo(1.2001, 10);
    }
  });

  it("returns unavailable when the analysis date is before the dataset", () => {
    const root = tempRoot();
    mkdirSync(join(root, "D1"));
    writeFileSync(
      join(root, "D1", "2024-01-15.csv"),
      csv(["2024-01-15 00:00:00,1.1,1.2,1.0,1.1,1"]),
    );
    const read = readClosedNativeCandles("D1", {
      root,
      lookbackBars: 5,
      asOfMs: Date.UTC(2024, 0, 14),
    });
    expect(read).toMatchObject({
      status: "unavailable",
      reason: "D1 has no candles on or before the analysis date.",
    });
  });

  it("uses the last available candles when the analysis date is after the dataset", () => {
    const root = tempRoot();
    mkdirSync(join(root, "D1"));
    writeFileSync(
      join(root, "D1", "2024-01-15.csv"),
      csv(["2024-01-15 00:00:00,1.1,1.2,1.0,1.15,1"]),
    );
    const read = readClosedNativeCandles("D1", {
      root,
      lookbackBars: 5,
      asOfMs: Date.UTC(2024, 1, 1),
    });
    expect(read.status).toBe("ok");
    if (read.status === "ok") {
      expect(read.candles[0]?.close).toBe(1.15);
      expect(read.warnings.some((warning) => warning.includes("requested lookback"))).toBe(true);
    }
  });

  it("warns when a short historical window cannot fill the lookback", () => {
    const root = tempRoot();
    mkdirSync(join(root, "H1"));
    writeFileSync(
      join(root, "H1", "2024-01-15.csv"),
      csv([
        "2024-01-15 00:00:00,1.1,1.2,1.0,1.11,1",
        "2024-01-15 01:00:00,1.11,1.2,1.0,1.12,1",
        "2024-01-15 02:00:00,1.12,1.2,1.0,1.13,1",
      ]),
    );
    const read = readClosedNativeCandles("H1", {
      root,
      lookbackBars: 10,
      asOfMs: Date.UTC(2024, 0, 15, 4, 0, 0),
    });
    expect(read.status).toBe("ok");
    if (read.status === "ok") {
      expect(read.candles).toHaveLength(3);
      expect(read.warnings.some((warning) => warning.includes("Only 3 closed candles"))).toBe(true);
    }
  });
});
