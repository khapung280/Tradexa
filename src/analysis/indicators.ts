/**
 * Indicator formulas. A value is null until its warm-up is complete.
 *
 * EMA: seed at index period-1 is the SMA of the first `period` values.
 * k = 2 / (period + 1)
 * EMA_i = price_i * k + EMA_(i-1) * (1 - k)
 *
 * RSI (Wilder): the first average gain and loss are arithmetic means of the
 * first `period` close-to-close changes. Later averages are
 * (previous * (period - 1) + current) / period.
 * RSI is 100 when average loss is 0 and average gain is positive.
 * RSI is 50 when both averages are 0.
 * Otherwise RSI = 100 - 100 / (1 + averageGain / averageLoss).
 * The first RSI is at index `period`.
 *
 * MACD: fast EMA minus slow EMA once both exist. The signal line is an EMA of
 * the MACD line, seeded with an SMA. Histogram = MACD - signal.
 *
 * ATR: the first true range is high-low because no previous close exists.
 * Later true range is the maximum of high-low, abs(high-previousClose), and
 * abs(low-previousClose). The first ATR is the SMA of the first `period`
 * true ranges, then Wilder smoothing is used. ATR is a range, not a direction.
 */

export type MacdPoint = {
  macd: number | null;
  signal: number | null;
  histogram: number | null;
};

export type Crossover = "bullish" | "bearish" | "none";

export type OhlcBar = {
  high: number;
  low: number;
  close: number;
  open?: number;
};

export function ema(values: readonly number[], period: number): (number | null)[] {
  assertPeriod(period, "EMA");
  assertFiniteNumbers(values, "EMA");
  const result: (number | null)[] = Array.from({ length: values.length }, () => null);
  if (values.length < period) {
    return result;
  }

  let sum = 0;
  for (let index = 0; index < period; index += 1) {
    sum += valueAt(values, index);
  }
  let current = sum / period;
  result[period - 1] = current;
  const k = 2 / (period + 1);
  for (let index = period; index < values.length; index += 1) {
    const price = valueAt(values, index);
    current = price * k + current * (1 - k);
    result[index] = current;
  }
  return result;
}

export function rsi(closes: readonly number[], period = 14): (number | null)[] {
  assertPeriod(period, "RSI");
  assertFiniteNumbers(closes, "RSI");
  const result: (number | null)[] = Array.from({ length: closes.length }, () => null);
  if (closes.length <= period) {
    return result;
  }

  let gainSum = 0;
  let lossSum = 0;
  for (let index = 1; index <= period; index += 1) {
    const change = valueAt(closes, index) - valueAt(closes, index - 1);
    if (change > 0) {
      gainSum += change;
    } else if (change < 0) {
      lossSum -= change;
    }
  }

  let averageGain = gainSum / period;
  let averageLoss = lossSum / period;
  result[period] = rsiFromAverages(averageGain, averageLoss);

  for (let index = period + 1; index < closes.length; index += 1) {
    const change = valueAt(closes, index) - valueAt(closes, index - 1);
    const gain = change > 0 ? change : 0;
    const loss = change < 0 ? -change : 0;
    averageGain = (averageGain * (period - 1) + gain) / period;
    averageLoss = (averageLoss * (period - 1) + loss) / period;
    result[index] = rsiFromAverages(averageGain, averageLoss);
  }
  return result;
}

export function macd(
  closes: readonly number[],
  fast = 12,
  slow = 26,
  signalPeriod = 9,
): MacdPoint[] {
  assertPeriod(fast, "MACD fast");
  assertPeriod(slow, "MACD slow");
  assertPeriod(signalPeriod, "MACD signal");
  if (fast >= slow) {
    throw new Error("MACD fast period must be shorter than the slow period.");
  }
  assertFiniteNumbers(closes, "MACD");

  const fastEma = ema(closes, fast);
  const slowEma = ema(closes, slow);
  const points: MacdPoint[] = closes.map(() => ({ macd: null, signal: null, histogram: null }));
  const macdValues: number[] = [];
  const macdIndexes: number[] = [];

  for (let index = 0; index < closes.length; index += 1) {
    const fastValue = fastEma[index];
    const slowValue = slowEma[index];
    if (fastValue === undefined || slowValue === undefined || fastValue === null || slowValue === null) {
      continue;
    }
    const line = fastValue - slowValue;
    const point = points[index];
    if (point === undefined) {
      throw new Error("MACD point is missing.");
    }
    point.macd = line;
    macdValues.push(line);
    macdIndexes.push(index);
  }

  const signal = ema(macdValues, signalPeriod);
  for (let index = 0; index < signal.length; index += 1) {
    const signalValue = signal[index];
    const candleIndex = macdIndexes[index];
    if (signalValue === undefined || signalValue === null || candleIndex === undefined) {
      continue;
    }
    const point = points[candleIndex];
    if (point === undefined || point.macd === null) {
      continue;
    }
    point.signal = signalValue;
    point.histogram = point.macd - signalValue;
  }
  return points;
}

export function crossoverFromDiffs(previous: number, current: number): Crossover {
  if (!Number.isFinite(previous) || !Number.isFinite(current)) {
    throw new Error("Crossover inputs must be finite.");
  }
  if (previous <= 0 && current > 0) {
    return "bullish";
  }
  if (previous >= 0 && current < 0) {
    return "bearish";
  }
  return "none";
}

export function macdCrossover(points: readonly MacdPoint[]): Crossover {
  const defined: MacdPoint[] = [];
  for (const point of points) {
    if (point.macd !== null && point.signal !== null) {
      defined.push(point);
    }
  }
  if (defined.length < 2) {
    return "none";
  }
  const previous = defined[defined.length - 2];
  const current = defined[defined.length - 1];
  if (
    previous === undefined ||
    current === undefined ||
    previous.macd === null ||
    previous.signal === null ||
    current.macd === null ||
    current.signal === null
  ) {
    return "none";
  }
  return crossoverFromDiffs(previous.macd - previous.signal, current.macd - current.signal);
}

export function emaCrossover(
  fast: readonly (number | null)[],
  slow: readonly (number | null)[],
): Crossover {
  const diffs: number[] = [];
  const length = Math.min(fast.length, slow.length);
  for (let index = 0; index < length; index += 1) {
    const fastValue = fast[index];
    const slowValue = slow[index];
    if (fastValue === undefined || slowValue === undefined || fastValue === null || slowValue === null) {
      continue;
    }
    diffs.push(fastValue - slowValue);
  }
  if (diffs.length < 2) {
    return "none";
  }
  const previous = diffs[diffs.length - 2];
  const current = diffs[diffs.length - 1];
  if (previous === undefined || current === undefined) {
    return "none";
  }
  return crossoverFromDiffs(previous, current);
}

export function trueRanges(bars: readonly OhlcBar[]): number[] {
  const ranges: number[] = [];
  for (let index = 0; index < bars.length; index += 1) {
    const bar = bars[index];
    if (bar === undefined) {
      throw new Error("ATR candle is missing.");
    }
    assertOhlc(bar);
    const range = bar.high - bar.low;
    if (index === 0) {
      ranges.push(range);
      continue;
    }
    const previous = bars[index - 1];
    if (previous === undefined) {
      throw new Error("ATR previous candle is missing.");
    }
    ranges.push(
      Math.max(range, Math.abs(bar.high - previous.close), Math.abs(bar.low - previous.close)),
    );
  }
  return ranges;
}

export function atr(bars: readonly OhlcBar[], period = 14): (number | null)[] {
  assertPeriod(period, "ATR");
  const ranges = trueRanges(bars);
  const result: (number | null)[] = Array.from({ length: ranges.length }, () => null);
  if (ranges.length < period) {
    return result;
  }

  let sum = 0;
  for (let index = 0; index < period; index += 1) {
    sum += valueAt(ranges, index);
  }
  let current = sum / period;
  result[period - 1] = current;
  for (let index = period; index < ranges.length; index += 1) {
    current = (current * (period - 1) + valueAt(ranges, index)) / period;
    result[index] = current;
  }
  return result;
}

export type VolatilityLabel = "elevated" | "typical" | "quiet" | "unavailable";

/** Latest ATR compared with the median of the last 20 available ATR values, including itself. */
export function atrVolatility(values: readonly (number | null)[]): VolatilityLabel {
  const recent: number[] = [];
  for (let index = values.length - 1; index >= 0 && recent.length < 20; index -= 1) {
    const value = values[index];
    if (value !== undefined && value !== null) {
      recent.push(value);
    }
  }
  const latest = recent[0];
  if (latest === undefined || recent.length < 2) {
    return "unavailable";
  }
  const sorted = [...recent].sort((left, right) => left - right);
  const median = sorted[Math.floor(sorted.length / 2)];
  if (median === undefined) {
    return "unavailable";
  }
  if (median === 0) {
    return latest === 0 ? "typical" : "elevated";
  }
  if (latest > median * 1.5) {
    return "elevated";
  }
  if (latest < median * (2 / 3)) {
    return "quiet";
  }
  return "typical";
}

export function rsiFromAverages(averageGain: number, averageLoss: number): number {
  if (!Number.isFinite(averageGain) || !Number.isFinite(averageLoss) || averageGain < 0 || averageLoss < 0) {
    throw new Error("RSI averages must be finite and non-negative.");
  }
  if (averageGain === 0 && averageLoss === 0) {
    return 50;
  }
  if (averageLoss === 0) {
    return 100;
  }
  const relativeStrength = averageGain / averageLoss;
  return 100 - 100 / (1 + relativeStrength);
}

function assertPeriod(period: number, name: string): void {
  if (!Number.isInteger(period) || period < 1) {
    throw new Error(`${name} period must be a positive integer.`);
  }
}

function assertFiniteNumbers(values: readonly number[], name: string): void {
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === undefined || !Number.isFinite(value)) {
      throw new Error(`${name} input must be finite.`);
    }
  }
}

function assertOhlc(bar: OhlcBar): void {
  if (!Number.isFinite(bar.high) || !Number.isFinite(bar.low) || !Number.isFinite(bar.close)) {
    throw new Error("ATR candle values must be finite.");
  }
  if (bar.high < bar.low || bar.high < bar.close || bar.low > bar.close) {
    throw new Error("ATR candle high/low/close is invalid.");
  }
  if (bar.open !== undefined && (!Number.isFinite(bar.open) || bar.high < bar.open || bar.low > bar.open)) {
    throw new Error("ATR candle open is invalid.");
  }
}

function valueAt(values: readonly number[], index: number): number {
  const value = values[index];
  if (value === undefined) {
    throw new Error("Missing series value.");
  }
  return value;
}
