import { readFile } from "node:fs/promises";

// Compare the same listings/seeds; include doji instead of improving ratios by excluding them.
const [beforePath, afterPath] = process.argv.slice(2);
if (!beforePath || !afterPath) throw new Error("Usage: bun scripts/analyze-candle-audit.mjs before.json after.json");
const before = JSON.parse(await readFile(beforePath, "utf8"));
const after = JSON.parse(await readFile(afterPath, "utf8"));
const key = row => `${row.code}:${row.seed}`;
if (new Set(before.map(key)).size !== before.length || new Set(after.map(key)).size !== after.length)
  throw new Error("Duplicate listing/seed");
if (before.length !== after.length || before.some(row => !after.some(other => key(row) === key(other))))
  throw new Error("Listing/seed sets differ");
function metrics(rows) {
  const bars = rows.flatMap(row => row.candles.filter(bar => bar[0] > 0));
  let wickBp = 0, ratio = 0, long = 0, flat = 0, doji = 0;
  for (const [, open, high, low, close] of bars) {
    const range = high - low, body = Math.abs(close - open), wick = range - body;
    wickBp += wick / open * 10_000;
    if (!body) doji++;
    if (!range) { flat++; continue; }
    ratio += wick / range;
    // Four or more ticks distinguishes repetitive wide wicks from a one-tick spread.
    const tick = open < 2000 ? 1 : open < 5000 ? 5 : open < 20000 ? 10 : open < 50000 ? 50 : open < 200000 ? 100 : open < 500000 ? 500 : 1000;
    if (range >= 4 * tick && wick / range >= .8) long++;
  }
  return { bars: bars.length, wickBp: wickBp / bars.length, wickRatio: ratio / Math.max(1, bars.length - flat),
    long, doji, flat, fills: rows.reduce((sum, row) => sum + row.path.fills, 0),
    longestQuietMs: Math.max(...rows.map(row => row.path.longestQuiet)) };
}
const codes = [...new Set(before.map(row => row.code))];
const report = codes.map(code => ({ code,
  before: metrics(before.filter(row => row.code === code)),
  after: metrics(after.filter(row => row.code === code)) }));
console.log(JSON.stringify({ seeds: [...new Set(before.map(row => row.seed))],
  total: { before: metrics(before), after: metrics(after) }, symbols: report }, null, 2));
