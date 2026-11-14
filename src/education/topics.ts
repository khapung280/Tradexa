export type EducationTopic = {
  id: string;
  title: string;
  paragraphs: readonly string[];
};

export const EDUCATION_DISCLAIMER =
  "This is education only. Tradexa does not give trading signals or personalized financial advice.";

export const EDUCATION_TOPIC_IDS = [
  "basics",
  "pips",
  "lots",
  "spreads",
  "leverage",
  "trend",
  "support-resistance",
  "price-action",
] as const;

export type EducationTopicId = (typeof EDUCATION_TOPIC_IDS)[number];

export const EDUCATION_TOPICS: readonly EducationTopic[] = [
  {
    id: "basics",
    title: "Forex basics",
    paragraphs: [
      "Forex is the market where one currency is exchanged for another, such as euros for US dollars.",
      "Prices are quoted in pairs. The first currency is the base; the second is the quote. A pair shows how much of the quote currency it takes to equal one unit of the base.",
      "People use Forex to travel, to settle business payments, and to try to benefit from price changes. Tradexa only explains how the market is described, not what you should do.",
      "A quote on a screen is not a recommendation. Always treat educational examples as illustrations.",
    ],
  },
  {
    id: "pips",
    title: "Pips",
    paragraphs: [
      "A pip is a small, standard step in a currency pair's price. For most pairs quoted to four decimal places, one pip is 0.0001.",
      "Yen pairs are often quoted to two decimal places, so one pip is commonly 0.01. Many platforms also show a fifth (or third) digit called a pipette, which is a tenth of a pip.",
      "Traders talk in pips so they can describe a move without mixing up different pair formats. A pip is a unit of measurement, not a forecast.",
      "Example: if a four-decimal pair moves from 1.1000 to 1.1008, that is an 8-pip change. That number does not tell you what happens next.",
    ],
  },
  {
    id: "lots",
    title: "Lots",
    paragraphs: [
      "A lot is a standard size for a Forex position. It tells you how many units of the base currency the position represents.",
      "A standard lot is often 100,000 units, a mini lot 10,000, and a micro lot 1,000. Brokers may also offer other sizes.",
      "Larger lots mean each pip is worth more in account currency. Smaller lots reduce the size of each pip's effect. Size is a risk choice, not a strategy.",
      "Lot size does not say whether a price will rise or fall. It only scales how large a move would be for that position.",
    ],
  },
  {
    id: "spreads",
    title: "Spreads",
    paragraphs: [
      "The spread is the gap between the two prices a broker shows: the bid and the ask. One is the price available to close a base-currency position; the other is the price available to open one. You do not need those labels to use this lesson.",
      "A narrower spread means less cost to open and later close a position, all else equal. A wider spread means more cost.",
      "Spreads can change with liquidity, news, and the pair. Major pairs often have smaller spreads than less-traded pairs.",
      "The spread is a transaction cost. It is not a hint about the next move in price.",
    ],
  },
  {
    id: "leverage",
    title: "Leverage",
    paragraphs: [
      "Leverage lets a trader control a larger position than the cash posted as margin. A 50:1 figure means the posted margin is a small fraction of the position size.",
      "Leverage magnifies both gains and losses. A small price change can have a large effect on the account when leverage is high.",
      "Margin is the amount set aside for that position. If losses grow, the broker may close positions to limit further loss. That process is about account risk, not a market prediction.",
      "Leverage is a tool for sizing, not a reason to expect a price to move. Beginners often start by understanding how large a move they can afford, not how much leverage is available.",
    ],
  },
  {
    id: "trend",
    title: "Trend",
    paragraphs: [
      "A trend is a rough description of direction on a chart over a chosen period: higher highs and higher lows, lower highs and lower lows, or no clear direction (a range).",
      "Trends depend on the timeframe. A chart of one hour can slope one way while a daily chart looks different. Naming a trend is about the window you are looking at.",
      "Trends can pause, reverse, or continue. A label such as \"uptrend\" is a description of past swings, not a promise of the next swing.",
      "Use the idea of trend to read structure. Do not treat it as an instruction to open a position.",
    ],
  },
  {
    id: "support-resistance",
    title: "Support and resistance",
    paragraphs: [
      "Support is a price area where a decline has often slowed or turned. Resistance is an area where a rise has often slowed or turned. They are zones, not exact lines.",
      "These areas come from where price reacted before. Other traders may watch the same places, which is why they sometimes matter again. Sometimes they fail.",
      "When price moves through a well-watched area, that area can change role in later descriptions. That is a way of talking about the chart, not a rule that must hold.",
      "Support and resistance help you describe where reactions happened. They are not targets and they are not orders to place.",
    ],
  },
  {
    id: "price-action",
    title: "Price action",
    paragraphs: [
      "Price action means reading the movement of price itself: swings, pauses, breakouts from a range, and how candles or bars group together.",
      "A single candle is one period's open, high, low, and close. Patterns of several candles can describe hesitation or a strong run. Descriptions are not probabilities you can count on.",
      "Price action is often combined with trend and with support or resistance, still as a reading of the chart you already have.",
      "This lesson does not name setups to trade. It only names how charts are described so later tools can fill the same structure with real data, when that exists.",
    ],
  },
];

export function findTopic(rawId: string): EducationTopic | undefined {
  const id = rawId.trim().toLowerCase();
  return EDUCATION_TOPICS.find((topic) => topic.id === id);
}

export function formatTopicList(): string {
  const lines = [
    "Tradexa Forex lessons:",
    "",
    ...EDUCATION_TOPICS.map((topic) => `/learn ${topic.id} — ${topic.title}`),
    "",
    "Send /learn followed by a topic id, for example /learn pips.",
    "",
    EDUCATION_DISCLAIMER,
  ];
  return lines.join("\n");
}

export function formatTopic(topic: EducationTopic): string {
  return [topic.title, "", ...topic.paragraphs, "", EDUCATION_DISCLAIMER].join("\n");
}
