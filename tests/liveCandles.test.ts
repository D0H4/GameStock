import test from "node:test";
import assert from "node:assert/strict";
import { CANDLE_INTERVAL_MS, collectLiveCandles, mergeLiveCandles } from "../src/market/liveCandles";
import type { ChartSample } from "../src/market/chartGeometry";

const start = Date.parse("2026-10-22T10:00:00Z");
const tick = (seconds: number, price: number) => ({ at: start + seconds * 1000, price });
const recorded = (at: number, updatedSeconds: number, close = 100): ChartSample => ({ at, price: close, candle: {
  recordedAt: new Date(at).toISOString(), updatedAt: new Date(start + updatedSeconds * 1000).toISOString(),
  openPrice: 100, highPrice: Math.max(110, close), lowPrice: Math.min(90, close), closePrice: close, volume: 20,
} });

test("each incoming fill moves the current candle close and extends its high and low", () => {
  const first = collectLiveCandles([], [tick(1, 100)], 60_000);
  const higher = collectLiveCandles(first, [tick(2, 120)], 60_000);
  const lower = collectLiveCandles(higher, [tick(3, 80)], 60_000);
  assert.deepEqual([lower[0].open, lower[0].high, lower[0].low, lower[0].close], [100, 120, 80, 80]);
  assert.equal(first[0].close, 100, "previous snapshots stay immutable");
});

test("a new minute creates a real candle and never fills inactive gaps", () => {
  const live = collectLiveCandles([], [tick(185, 130), tick(61, 120), tick(59, 110), tick(1, 100)], 60_000);
  assert.deepEqual(live.map(bar=>bar.at), [start, start+60_000, start+180_000]);
  assert.deepEqual(live.map(bar=>[bar.open,bar.close]), [[100,110],[120,120],[130,130]]);
});

test("same-millisecond trades keep execution order and repeating snapshots is idempotent", () => {
  const fills = [tick(10, 120), tick(10, 80), tick(10, 100)];
  const live = collectLiveCandles([], fills, 60_000);
  assert.deepEqual([live[0].open,live[0].high,live[0].low,live[0].close], [100,120,80,120]);
  assert.equal(collectLiveCandles(live, fills, 60_000), live);
  const late = collectLiveCandles(live, [tick(5, 90)], 60_000);
  assert.equal(late[0].close, 120);
});

test("an older REST response cannot rewind a newer live close or discard its wick", () => {
  const history = [recorded(start - 60_000, -1), recorded(start, 5)];
  const live = collectLiveCandles([], [tick(12, 115), tick(11, 130), tick(10, 85)], 60_000);
  const merged = mergeLiveCandles(history, live);
  assert.equal(merged[0], history[0], "closed historical candles stay unchanged");
  assert.deepEqual([merged[1].candle!.openPrice, merged[1].candle!.highPrice, merged[1].candle!.lowPrice, merged[1].price], [100,130,85,115]);
  assert.equal(history[1].price, 100);
  assert.equal(mergeLiveCandles(merged,live), merged);
});

test("a newer server aggregate replaces the live candle after reconciliation", () => {
  const history = [recorded(start, 20, 125)];
  const live = collectLiveCandles([], [tick(12, 115)], 60_000);
  assert.equal(mergeLiveCandles(history,live), history);
  const rolled = [recorded(start, 59, 125), recorded(start+60_000, 65, 135)];
  assert.equal(mergeLiveCandles(rolled,live), rolled);
});

test("streamed candles appear before a delayed history response, without duplicate buckets", () => {
  const live = collectLiveCandles([], [tick(61, 120), tick(59, 110)], 60_000);
  assert.equal(mergeLiveCandles([], live).length, 2);
  const merged = mergeLiveCandles([recorded(start, 50)],live);
  assert.deepEqual(merged.map(sample=>sample.at), [start,start+60_000]);
  assert.equal(merged[0].price, 110);
});

test("every range follows the server interval and invalid ticks never affect candles", () => {
  for (const interval of Object.values(CANDLE_INTERVAL_MS)) {
    const live = collectLiveCandles([], [tick(1,100), {at:NaN,price:300}, tick(2,0)], interval);
    assert.equal(live.length, 1);
    assert.equal(live[0].at, Math.floor(tick(1,100).at/interval)*interval);
  }
});

test("live memory stays bounded during a long-running chart session", () => {
  const live = collectLiveCandles([], Array.from({length:300}, (_,i)=>tick((299-i)*60,100+i)), 60_000);
  assert.equal(live.length,128);
});
