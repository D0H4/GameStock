import { serverTimestamp } from "./format";

export type PricePoint = { recordedAt: string; price: number };

export const BUCKET_MS = 10 * 60_000;

/** 한국시간 오늘 0시. */
export function kstMidnight(now: number): number {
  const kst = new Date(now + 9 * 3_600_000);
  return Date.UTC(kst.getUTCFullYear(), kst.getUTCMonth(), kst.getUTCDate()) - 9 * 3_600_000;
}

/** 오늘 0시부터 지금까지 10분 칸마다 그 시점의 마지막 체결가. 기록이 없으면 빈 배열. */
export function bucketPrices(points: PricePoint[], now: number): number[] {
  const series = points
    .map((point) => ({ at: serverTimestamp(point.recordedAt), price: point.price }))
    .filter((point) => Number.isFinite(point.at) && point.price > 0)
    .sort((a, b) => a.at - b.at);
  if (series.length === 0) return [];
  const prices: number[] = [];
  let cursor = 0;
  let last = series[0].price;
  for (let at = kstMidnight(now); at <= now; at += BUCKET_MS) {
    while (cursor < series.length && series[cursor].at <= at) last = series[cursor++].price;
    prices.push(last);
  }
  return prices;
}
