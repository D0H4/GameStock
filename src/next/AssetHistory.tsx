import { useEffect, useState } from "react";
import { apiFetch } from "../lib/api";
import { rate, signedWon, won } from "../market/format";
import { AnimatedNav } from "./AnimatedNav";

type Point = { date: string; netAsset: number };

const RANGES = [[7, "7일"], [30, "30일"], [90, "90일"], [365, "1년"]] as const;
const WIDTH = 640;
const HEIGHT = 160;
const dayLabel = (date: string) => { const [, month, day] = date.split("-"); return `${Number(month)}.${Number(day)}`; };

/**
 * 하루 마감 순자산을 이은 선. 마지막 점은 지금 평가액이다.
 * 기간 첫날 값을 점선으로 깔아 그 위아래로 얼마나 움직였는지 읽게 한다.
 */
export function AssetHistory() {
  const [days, setDays] = useState<number>(30);
  const [points, setPoints] = useState<Point[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    setFailed(false);
    apiFetch<Point[]>(`/api/asset-history?days=${days}`)
      .then((next) => { if (active) setPoints(next); })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, [days, attempt]);

  const first = points?.[0];
  const last = points?.at(-1);
  const change = first && last ? last.netAsset - first.netAsset : 0;
  const ratio = first && first.netAsset > 0 ? change / first.netAsset : 0;
  const tone = change > 0 ? "up" : change < 0 ? "down" : "flat";

  let line = "";
  let baseY = HEIGHT / 2;
  let dots: Array<{ x: number; y: number; point: Point }> = [];
  if (points && points.length > 1 && first) {
    const values = points.map((point) => point.netAsset);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const pad = Math.max(1, (max - min) * 0.12);
    const y = (value: number) => HEIGHT - ((value - (min - pad)) / (max - min + pad * 2)) * HEIGHT;
    dots = points.map((point, index) => ({ x: (index / (points.length - 1)) * WIDTH, y: y(point.netAsset), point }));
    line = dots.map((dot, index) => `${index ? "L" : "M"}${dot.x.toFixed(1)},${dot.y.toFixed(1)}`).join(" ");
    baseY = y(first.netAsset);
  }

  return (
    <section className="nx-asset-history" aria-labelledby="nx-asset-history-title">
      <header>
        <h2 id="nx-asset-history-title">자산 추이</h2>
        <AnimatedNav as="div" activeKey={String(days)} className="nx-tabs" role="group" aria-label="기간">
          {RANGES.map(([value, label]) => (
            <button key={value} type="button" className={days === value ? "is-active" : undefined} aria-pressed={days === value} onClick={() => setDays(value)}>{label}</button>
          ))}
        </AnimatedNav>
      </header>

      {failed ? (
        <p className="nx-note is-error" role="alert">
          자산 기록을 불러오지 못했어요. <button type="button" className="nx-link-button" onClick={() => setAttempt((current) => current + 1)}>다시 불러오기</button>
        </p>
      ) : !points ? <p className="nx-muted" role="status">자산 기록을 불러오고 있어요.</p>
        : points.length === 0 ? <p className="nx-muted">아직 남은 자산 기록이 없어요. 5분 안에 오늘 값이 기록돼요.</p>
        : <>
          <p className={`nx-asset-change nx-num is-${tone}`}>
            {points.length > 1 ? <>{signedWon(change)} ({rate(ratio)}) <span className="nx-muted">{dayLabel(first!.date)}부터</span></> : <span className="nx-muted">오늘 처음 기록했어요. 내일부터 선이 이어져요.</span>}
          </p>
          {points.length > 1 && (
            <figure className="nx-asset-plot">
              <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" role="img"
                aria-label={`${dayLabel(first!.date)} ${won(first!.netAsset)}에서 ${dayLabel(last!.date)} ${won(last!.netAsset)}`}>
                <line className="nx-asset-base" x1={0} x2={WIDTH} y1={baseY} y2={baseY} vectorEffect="non-scaling-stroke" />
                <path className={`nx-asset-line is-${tone}`} d={line} vectorEffect="non-scaling-stroke" />
              </svg>
              <figcaption className="nx-asset-axis nx-num"><span>{dayLabel(first!.date)}</span><span>오늘</span></figcaption>
            </figure>
          )}
          <table className="vh">
            <caption>날짜별 순자산</caption>
            <tbody>{points.map((point) => <tr key={point.date}><th scope="row">{point.date}</th><td>{won(point.netAsset)}</td></tr>)}</tbody>
          </table>
        </>}
    </section>
  );
}
