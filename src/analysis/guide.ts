/**
 * Beginner walkthrough of how traders study a chart.
 * No live quotes. These notes fill AnalysisReport.notes while data is unavailable.
 */
export const CHART_READING_NOTES = [
  "Live market data is not connected yet. The steps below are how to read a chart when a real feed exists. Tradexa will not invent prices or candles.",
  "1. Choose a timeframe first. A one-hour chart and a daily chart can describe different structure. Name the window before you name a trend.",
  "2. Trend: look for Higher Highs and Higher Lows (HH/HL), Lower Highs and Lower Lows (LH/LL), or a range (no clear HH/HL or LH/LL). That label describes past swings. It is not a promise of the next swing.",
  "3. Support and resistance are zones, not exact prices. Support is an area where a decline has often slowed. Resistance is an area where a rise has often slowed. Zones can fail.",
  "4. Price action is the recent path of price: swings, pauses, and breaks out of a range. Describe what already happened. Do not invent the next candle.",
  "5. Chart analysis is a reading of structure, not a trading signal. It does not tell you to open a position, place an order, or expect a guaranteed outcome.",
] as const;
