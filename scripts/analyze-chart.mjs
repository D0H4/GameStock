import { readFile } from "node:fs/promises";

// Exclude the opening minute. Flat candles are reported separately, not scored as short wicks.
for (const path of process.argv.length > 2 ? process.argv.slice(2) : ["public/simulation/before.json", "public/simulation/after.json"]) {
  const replay = JSON.parse(await readFile(path, "utf8"));
  const bars = new Map();
  for (const [at, open, high, low, close] of replay.seconds) {
    const minute = Math.floor(at / 60000);
    if (!minute || (minute + 1) * 60000 > replay.durationMs) continue;
    const bar = bars.get(minute);
    if (bar) { bar.high = Math.max(bar.high, high); bar.low = Math.min(bar.low, low); bar.close = close; }
    else bars.set(minute, { open, high, low, close });
  }
  const moving = [...bars.values()].filter(bar => bar.high > bar.low);
  const fractions = moving.map(bar => 1 - Math.abs(bar.close - bar.open) / (bar.high - bar.low));
  const mean = values => values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null;
  console.log(JSON.stringify({ path, code: replay.code, seed: replay.seed,
    bars: bars.size, flatBars: bars.size - moving.length,
    meanWickFraction: mean(fractions), bodyAtLeastHalf: fractions.filter(value => value <= .5).length,
    meanRange: mean(moving.map(bar => bar.high - bar.low)),
    meanBody: mean(moving.map(bar => Math.abs(bar.close - bar.open))),
  }, null, 2));
}
