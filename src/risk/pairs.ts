export const STANDARD_PIP_SIZE = 0.0001;
export const JPY_PIP_SIZE = 0.01;
export const STANDARD_LOT_UNITS = 100_000;

export type ForexPair = {
  symbol: string;
  base: string;
  quote: string;
  pipSize: number;
  unitsPerLot: number;
};

function definePair(base: string, quote: string): ForexPair {
  return {
    symbol: `${base}${quote}`,
    base,
    quote,
    pipSize: quote === "JPY" ? JPY_PIP_SIZE : STANDARD_PIP_SIZE,
    unitsPerLot: STANDARD_LOT_UNITS,
  };
}

export const FOREX_PAIRS: readonly ForexPair[] = [
  definePair("EUR", "USD"),
  definePair("GBP", "USD"),
  definePair("AUD", "USD"),
  definePair("NZD", "USD"),
  definePair("USD", "CAD"),
  definePair("USD", "CHF"),
  definePair("EUR", "GBP"),
  definePair("EUR", "CHF"),
  definePair("EUR", "AUD"),
  definePair("EUR", "CAD"),
  definePair("GBP", "AUD"),
  definePair("GBP", "CAD"),
  definePair("GBP", "CHF"),
  definePair("AUD", "CAD"),
  definePair("AUD", "CHF"),
  definePair("AUD", "NZD"),
  definePair("USD", "JPY"),
  definePair("EUR", "JPY"),
  definePair("GBP", "JPY"),
  definePair("AUD", "JPY"),
  definePair("NZD", "JPY"),
  definePair("CAD", "JPY"),
  definePair("CHF", "JPY"),
];

const PAIRS_BY_SYMBOL = new Map(FOREX_PAIRS.map((pair) => [pair.symbol, pair]));

export function normalizePairSymbol(raw: string): string {
  return raw.trim().toUpperCase().replace(/[^A-Z]/g, "");
}

export function findPair(raw: string): ForexPair | undefined {
  const symbol = normalizePairSymbol(raw);
  if (symbol.length === 0) {
    return undefined;
  }
  return PAIRS_BY_SYMBOL.get(symbol);
}
