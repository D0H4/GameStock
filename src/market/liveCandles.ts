import { serverTimestamp } from "./format";
import type { ChartSample } from "./chartGeometry";

type Tick = { at: number; price: number };
export type LiveCandle = { at: number; firstAt: number; lastAt: number; open: number; high: number; low: number; close: number };

/** Match the server's UTC/Unix bucket boundaries, including weekly candles. */
export const CANDLE_INTERVAL_MS = {
  realtime: 60_000, "1h": 60_000, "6h": 300_000, "12h": 600_000,
  "1d": 1_800_000, "1w": 7_200_000, "1m": 86_400_000, "1y": 604_800_000,
} as const;

/** Trade snapshots are newest first. Keep actual server times; never create bars from a client clock. */
export function collectLiveCandles(previous: LiveCandle[], ticks: readonly Tick[], interval: number): LiveCandle[] {
  const bars = new Map(previous.map(bar => [bar.at, bar]));
  let changed = false;
  // A stable sort preserves execution order for trades sharing a millisecond.
  const ordered = ticks.filter(tick => Number.isFinite(tick.at) && tick.at > 0 && Number.isFinite(tick.price) && tick.price > 0)
    .slice().reverse().sort((left, right) => left.at - right.at);
  for (const tick of ordered) {
    const at = Math.floor(tick.at / interval) * interval;
    const old = bars.get(at);
    const next: LiveCandle = old ? {
      at, firstAt: Math.min(old.firstAt, tick.at), lastAt: Math.max(old.lastAt, tick.at),
      open: tick.at < old.firstAt ? tick.price : old.open,
      high: Math.max(old.high, tick.price), low: Math.min(old.low, tick.price),
      close: tick.at >= old.lastAt ? tick.price : old.close,
    } : { at, firstAt: tick.at, lastAt: tick.at, open: tick.price, high: tick.price, low: tick.price, close: tick.price };
    if (!old || next.firstAt !== old.firstAt || next.lastAt !== old.lastAt || next.open !== old.open || next.high !== old.high || next.low !== old.low || next.close !== old.close) {
      bars.set(at, next);
      changed = true;
    }
  }
  if (!changed) return previous;
  const next = [...bars.values()].sort((left, right) => left.at - right.at).slice(-128);
  // Repeated snapshots may temporarily walk through same-time fills before reaching the same final close.
  return next.length === previous.length && next.every((bar, index) => {
    const old = previous[index];
    return bar.at === old.at && bar.firstAt === old.firstAt && bar.lastAt === old.lastAt && bar.open === old.open
      && bar.high === old.high && bar.low === old.low && bar.close === old.close;
  }) ? previous : next;
}

/** REST supplies historical OHLC; newer fills extend the open candle until REST catches up. */
export function mergeLiveCandles(history: ChartSample[], live: LiveCandle[]): ChartSample[] {
  if (!live.length) return history;
  const samples = new Map(history.map(sample => [sample.at, sample]));
  const firstAt = history[0]?.at ?? live[0].at;
  const lastRecordedAt = history.at(-1)?.at ?? Number.NEGATIVE_INFINITY;
  let changed = false;
  for (const bar of live) {
    if (bar.at < firstAt) continue;
    const recorded = samples.get(bar.at);
    const candle = recorded?.candle;
    // Closed REST bars are authoritative. Older servers without updatedAt can still extend the newest bar.
    if (bar.at < lastRecordedAt) continue;
    const recordedUpdate = candle?.updatedAt ? serverTimestamp(candle.updatedAt) : Number.NEGATIVE_INFINITY;
    if (recordedUpdate >= bar.lastAt) continue;
    const merged = {
      recordedAt: new Date(bar.at).toISOString(),
      openPrice: candle?.openPrice ?? bar.open,
      highPrice: Math.max(candle?.highPrice ?? bar.high, bar.high),
      lowPrice: Math.min(candle?.lowPrice ?? bar.low, bar.low),
      closePrice: bar.close,
      volume: candle?.volume ?? 0,
      updatedAt: new Date(bar.lastAt).toISOString(),
    };
    samples.set(bar.at, { at: bar.at, price: bar.close, candle: merged });
    changed = true;
  }
  return changed ? [...samples.values()].sort((left, right) => left.at - right.at) : history;
}
