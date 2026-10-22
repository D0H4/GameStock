export type ChartMode = "line" | "candle";
export type ChartCandle = { recordedAt: string; openPrice: number; highPrice: number; lowPrice: number; closePrice: number; volume: number; updatedAt?: string };
export type ChartSample = { at: number; price: number; candle?: ChartCandle };

export const clamp = (value: number, low: number, high: number) => Math.max(low, Math.min(high, value));

/** Keep recorded values intact; discard invalid bars and keep the final value of a duplicate timestamp. */
export function normalizeChartSamples(samples: ChartSample[]): ChartSample[] {
  const unique = new Map<number, ChartSample>();
  for (const sample of samples) {
    if (!Number.isFinite(sample.at) || !Number.isFinite(sample.price) || sample.price <= 0) continue;
    const bar = sample.candle;
    if (bar && (![bar.openPrice, bar.highPrice, bar.lowPrice, bar.closePrice].every(value => Number.isFinite(value) && value > 0)
      || bar.highPrice < Math.max(bar.openPrice, bar.closePrice) || bar.lowPrice > Math.min(bar.openPrice, bar.closePrice))) continue;
    unique.set(sample.at, sample);
  }
  return [...unique.values()].sort((a, b) => a.at - b.at);
}

/** Sorted timestamps support line-chart inspection and news markers without scanning every record. */
export function nearestSampleIndex(samples: ChartSample[], at: number): number {
  if (!samples.length) return -1;
  let left = 0;
  let right = samples.length - 1;
  while (left < right) {
    const mid = (left + right) >>> 1;
    if (samples[mid].at < at) left = mid + 1;
    else right = mid;
  }
  return left > 0 && at - samples[left - 1].at <= samples[left].at - at ? left - 1 : left;
}

/** Round a price scale to readable 1/2/5 increments, including flat and single-bar markets. */
export function chartPriceScale(low: number, high: number, targetTicks: number) {
  const span = Math.max(high - low, Math.abs(high) * 0.002, 1);
  const padding = span * 0.12;
  const rawStep = (span + padding * 2) / Math.max(2, targetTicks);
  const magnitude = 10 ** Math.floor(Math.log10(rawStep));
  const unit = rawStep / magnitude;
  const step = Math.max(1, (unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 5 ? 5 : 10) * magnitude);
  const bottom = Math.max(0, Math.floor((low - padding) / step) * step);
  const top = Math.max(bottom + step, Math.ceil((high + padding) / step) * step);
  const ticks: number[] = [];
  for (let i = 0; i <= Math.round((top - bottom) / step); i++) ticks.push(bottom + i * step);
  return { low: bottom, high: top, ticks };
}

/** All coordinates use CSS pixels. A 1px wick stays 1px at every viewport width. */
export function chartGeometry(samples: ChartSample[], mode: ChartMode, width: number, height: number, currentPrice: number) {
  const axisWidth = width < 400 ? 60 : 72;
  const plotWidth = Math.max(1, width - axisWidth);
  const plotHeight = Math.max(1, height - 28);
  const left = Math.min(8, plotWidth * 0.04);
  const right = Math.min(28, plotWidth * 0.08);
  const usableWidth = Math.max(1, plotWidth - left - right);
  const slot = usableWidth / Math.max(32, samples.length);
  const firstX = plotWidth - right - slot * (samples.length - 0.5);
  const firstAt = samples[0]?.at ?? 0;
  const lastAt = samples.at(-1)?.at ?? firstAt;
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  for (const sample of samples) {
    min = Math.min(min, mode === "candle" && sample.candle ? sample.candle.lowPrice : sample.price);
    max = Math.max(max, mode === "candle" && sample.candle ? sample.candle.highPrice : sample.price);
  }
  const live = Number.isFinite(currentPrice) && currentPrice > 0 ? currentPrice : samples.at(-1)?.price ?? 1;
  if (!samples.length) min = max = live;
  const scale = chartPriceScale(Math.min(min, live), Math.max(max, live), Math.max(3, Math.floor(plotHeight / 58)));
  const y = (price: number) => (scale.high - price) / (scale.high - scale.low) * plotHeight;
  const priceAtY = (position: number) => scale.high - clamp(position / plotHeight, 0, 1) * (scale.high - scale.low);
  const xAtIndex = (index: number) => mode === "candle" ? firstX + index * slot
    : lastAt > firstAt ? left + (samples[index].at - firstAt) / (lastAt - firstAt) * usableWidth : plotWidth / 2;
  const indexAtX = (position: number) => {
    if (!samples.length) return -1;
    if (mode === "candle") return clamp(Math.round((position - firstX) / slot), 0, samples.length - 1);
    return nearestSampleIndex(samples, firstAt + clamp((position - left) / usableWidth, 0, 1) * (lastAt - firstAt));
  };
  const bodyWidth = Math.max(1, Math.min(12, Math.floor(slot * 0.68)));
  return { axisWidth, plotWidth, plotHeight, min, max, scale, bodyWidth, xAtIndex, indexAtX, y, priceAtY };
}

export type ChartGeometry = ReturnType<typeof chartGeometry>;
