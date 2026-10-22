import test from "node:test";
import assert from "node:assert/strict";
import { chartGeometry, chartPriceScale, nearestSampleIndex, normalizeChartSamples } from "../src/market/chartGeometry";
import type { ChartSample } from "../src/market/chartGeometry";

const bars: ChartSample[] = Array.from({ length: 72 }, (_, index) => ({
  at: 1_700_000_000_000 + index * 60_000, price: 10_010 + index * 10,
  candle: { recordedAt: "", openPrice: 10_000 + index * 10, highPrice: 10_030 + index * 10, lowPrice: 9_980 + index * 10, closePrice: 10_010 + index * 10, volume: 20 },
}));

test("every candle center selects its own OHLC record on mobile and desktop", () => {
  for (const width of [200, 288, 390, 720, 1440]) {
    const g = chartGeometry(bars, "candle", width, 360, 10_800);
    bars.forEach((_, index) => assert.equal(g.indexAtX(g.xAtIndex(index)), index, `width=${width}, index=${index}`));
    assert.equal(g.indexAtX(-100), 0);
    assert.equal(g.indexAtX(width + 100), bars.length - 1);
    assert.ok(g.bodyWidth >= 1 && g.bodyWidth <= 12);
    assert.ok(g.xAtIndex(0) >= 0 && g.xAtIndex(71) < g.plotWidth);
  }
});

test("line inspection uses recorded time, including gaps and duplicate cleanup", () => {
  const irregular = [{ at: 0, price: 100 }, { at: 100, price: 200 }, { at: 1000, price: 300 }];
  assert.equal(nearestSampleIndex(irregular, 60), 1);
  assert.equal(nearestSampleIndex(irregular, 900), 2);
  assert.equal(nearestSampleIndex(irregular, -100), 0);
  assert.equal(nearestSampleIndex([], 0), -1);
  const g = chartGeometry(irregular, "line", 720, 300, 500);
  irregular.forEach((_, index) => assert.equal(g.indexAtX(g.xAtIndex(index)), index));
  assert.ok(g.xAtIndex(1) - g.xAtIndex(0) < g.xAtIndex(2) - g.xAtIndex(1));
});

test("flat, single-bar and empty markets have a finite scale", () => {
  for (const samples of [[], [{ at: 100, price: 1 }], [{ at: 100, price: 10000 }]]) {
    for (const mode of ["candle", "line"] as const) {
      const g = chartGeometry(samples, mode, 288, 310, samples[0]?.price ?? 10000);
      assert.ok(g.scale.high > g.scale.low);
      assert.ok(Number.isFinite(g.y(samples[0]?.price ?? 10000)));
      assert.ok(g.scale.ticks.every(Number.isFinite));
      assert.equal(g.indexAtX(0), samples.length ? 0 : -1);
    }
  }
});

test("the scale contains wicks and live prices without altering historical candles", () => {
  const before = structuredClone(bars);
  for (const current of [100, 50000]) {
    const g = chartGeometry(bars, "candle", 720, 360, current);
    assert.ok(g.y(current) >= 0 && g.y(current) <= g.plotHeight);
    for (const bar of bars) {
      assert.ok(g.y(bar.candle!.highPrice) >= 0);
      assert.ok(g.y(bar.candle!.lowPrice) <= g.plotHeight);
      assert.ok(Math.abs(g.priceAtY(g.y(bar.price)) - bar.price) < 0.0001);
    }
  }
  assert.deepEqual(bars, before);
});

test("invalid OHLC records are rejected and the last duplicate timestamp wins", () => {
  const invalid = { ...bars[0], at: -1, candle: { ...bars[0].candle!, highPrice: 1 } };
  const last = { at: bars[0].at, price: 12345 };
  const actual = normalizeChartSamples([bars[2], bars[0], invalid, { at: NaN, price: 10 }, { at: 30, price: 0 }, last]);
  assert.deepEqual(actual, [last, bars[2]]);
});

test("price ticks are readable, ordered and cover the requested range", () => {
  for (const [low, high] of [[1, 1], [12770, 12770], [90, 10200], [100000000, 100000100]]) {
    const scale = chartPriceScale(low, high, 5);
    assert.ok(scale.low <= low && scale.high >= high);
    assert.ok(scale.ticks.length >= 2 && scale.ticks.length <= 10);
    assert.ok(scale.ticks.every((tick, index) => Number.isInteger(tick) && (index === 0 || tick > scale.ticks[index - 1])));
  }
});
