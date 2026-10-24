import { StrictMode, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./simulation.css";

type Bar = [at: number, open: number, high: number, low: number, close: number, volume: number];
type Replay = { code: string; seed: number; durationMs: number; reference: number; seconds: Bar[]; version: string };
type Version = "before" | "after";
const format = new Intl.NumberFormat("ko-KR");
const elapsed = (ms: number) => `${Math.floor(ms / 60_000).toString().padStart(2, "0")}:${Math.floor(ms % 60_000 / 1000).toString().padStart(2, "0")}`;

function validate(value: unknown): Replay {
  const data = value as Replay;
  if (!data || !Array.isArray(data.seconds) || !data.seconds.length || !Number.isFinite(data.durationMs)
    || data.durationMs <= 0 || !Number.isFinite(data.reference) || data.reference <= 0 || typeof data.code !== "string") {
    throw new Error("시뮬레이션 기록을 읽을 수 없습니다.");
  }
  let previous = -1;
  for (const row of data.seconds) {
    if (!Array.isArray(row) || row.length !== 6 || !row.every(Number.isFinite)
      || row[0] <= previous || row[0] >= data.durationMs || row[3] <= 0 || row[5] <= 0
      || row[2] < Math.max(row[1], row[4]) || row[3] > Math.min(row[1], row[4])) {
      throw new Error("시뮬레이션 체결 기록이 올바르지 않습니다.");
    }
    previous = row[0];
  }
  return data;
}

/** Aggregate only observed executions. Empty minutes stay empty; no synthetic wick or price. */
function candles(seconds: Bar[], until: number): Bar[] {
  const bars: Bar[] = [];
  for (const row of seconds) {
    // A second becomes visible after it has fully elapsed, never exposing future fills.
    if (row[0] + 1000 > until) break;
    const at = Math.floor(row[0] / 60_000) * 60_000;
    const current = bars.at(-1);
    if (!current || current[0] !== at) bars.push([at, row[1], row[2], row[3], row[4], row[5]]);
    else {
      current[2] = Math.max(current[2], row[2]);
      current[3] = Math.min(current[3], row[3]);
      current[4] = row[4];
      current[5] += row[5];
    }
  }
  return bars.slice(-60);
}

function Chart({ bars, reference }: { bars: Bar[]; reference: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  useEffect(() => {
    const element = canvas.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setSize({ width: entry.contentRect.width, height: entry.contentRect.height }));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    const element = canvas.current;
    if (!element || !size.width || !size.height) return;
    const frame = requestAnimationFrame(() => {
      const start = performance.now();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const { width, height } = size;
      element.width = Math.round(width * dpr);
      element.height = Math.round(height * dpr);
      const ctx = element.getContext("2d");
      if (!ctx) return;
      ctx.scale(dpr, dpr);
      const right = width < 500 ? 62 : 80, bottom = 35, top = 24;
      const plotWidth = width - right, plotHeight = height - bottom - top;
      const values = bars.flatMap(bar => [bar[2], bar[3]]);
      const low = values.length ? Math.min(...values) : reference;
      const high = values.length ? Math.max(...values) : reference;
      const padding = Math.max((high - low) * .15, reference * .001);
      const min = low - padding, max = high + padding;
      const y = (price: number) => top + (max - price) / (max - min) * plotHeight;
      const slots = width < 600 ? 30 : 60;
      const visible = bars.slice(-slots);
      const step = plotWidth / slots;
      const first = visible[0]?.[0] ?? 0;
      const x = (at: number) => ((at - first) / 60_000 + .5) * step;
      ctx.font = "12px Pretendard Variable, sans-serif";
      ctx.textBaseline = "middle";
      ctx.lineWidth = 1;
      for (let index = 0; index < 6; index++) {
        const price = min + (max - min) * index / 5;
        const py = Math.round(y(price)) + .5;
        ctx.strokeStyle = "#262b30";
        ctx.beginPath(); ctx.moveTo(0, py); ctx.lineTo(plotWidth, py); ctx.stroke();
        ctx.fillStyle = "#9aa4ac";
        ctx.fillText(format.format(Math.round(price)), plotWidth + 12, py);
      }
      for (let index = 0; index < slots; index += slots === 30 ? 6 : 10) {
        const px = x(first + index * 60_000);
        ctx.fillStyle = "#9aa4ac";
        ctx.fillText(elapsed(first + index * 60_000), Math.max(0, px - 14), height - 12);
      }
      const bodyWidth = Math.max(2, Math.min(15, step * .62));
      for (const [at, open, highPrice, lowPrice, close] of visible) {
        const px = Math.round(x(at)) + .5;
        ctx.strokeStyle = ctx.fillStyle = close >= open ? "#1ca990" : "#e95c70";
        ctx.beginPath(); ctx.moveTo(px, y(highPrice)); ctx.lineTo(px, y(lowPrice)); ctx.stroke();
        ctx.fillRect(px - bodyWidth / 2, Math.min(y(open), y(close)), bodyWidth, Math.max(1, Math.abs(y(open) - y(close))));
      }
      const last = visible.at(-1);
      if (last) {
        const py = y(last[4]);
        ctx.strokeStyle = ctx.fillStyle = last[4] >= last[1] ? "#1ca990" : "#e95c70";
        ctx.setLineDash([2, 5]);
        ctx.beginPath(); ctx.moveTo(0, py); ctx.lineTo(plotWidth, py); ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillRect(plotWidth + 5, py - 12, right - 7, 24);
        ctx.fillStyle = "#111518";
        ctx.fillText(format.format(last[4]), plotWidth + 12, py);
      }
      // Local QA timing, kept off the product surface. No continuous animation loop.
      element.dataset.drawMs = (performance.now() - start).toFixed(3);
    });
    return () => cancelAnimationFrame(frame);
  }, [bars, reference, size]);
  const last = bars.at(-1);
  return <canvas ref={canvas} role="img" aria-label={`1분봉 가격 차트, ${bars.length}개 봉${last ? `, 현재 ${format.format(last[4])}원` : ""}`} />;
}

function Simulation() {
  const [records, setRecords] = useState<Record<Version, Replay> | null>(null);
  const [error, setError] = useState("");
  const [version, setVersion] = useState<Version>("after");
  const [cursor, setCursor] = useState(20 * 60_000);
  const [playing, setPlaying] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    Promise.all((["before", "after"] as const).map(async name => {
      const response = await fetch(`/simulation/${name}.json`, { signal: controller.signal });
      if (!response.ok) throw new Error("시뮬레이션 기록을 불러오지 못했습니다.");
      return validate(await response.json());
    })).then(([before, after]) => {
      if (before.code !== after.code || before.seed !== after.seed || before.durationMs !== after.durationMs) {
        throw new Error("수정 전후의 시뮬레이션 조건이 다릅니다.");
      }
      setRecords({ before, after });
      setCursor(Math.min(20 * 60_000, after.durationMs));
    }).catch((reason: Error) => { if (!controller.signal.aborted) setError(reason.message); });
    return () => controller.abort();
  }, []);
  const record = records?.[version];
  const duration = record?.durationMs ?? 60 * 60_000;
  const ended = cursor >= duration;
  useEffect(() => {
    if (!playing || !records || ended) return;
    let previous = performance.now();
    const timer = window.setInterval(() => {
      const now = performance.now();
      const delta = document.hidden ? 0 : Math.min(now - previous, 250) * 30;
      previous = now;
      setCursor(current => Math.min(duration, current + delta));
    }, 100);
    return () => window.clearInterval(timer);
  }, [playing, records, duration, ended]);
  const bars = useMemo(() => candles(record?.seconds ?? [], Math.floor(cursor / 1000) * 1000), [record, cursor]);
  const last = bars.at(-1);
  return <main>
    <header>
      <div className="identity"><h1>{record?.code ?? "UMA"}</h1><span>1분봉 · 시뮬레이션</span></div>
      <div className="controls">
        <select aria-label="가격 엔진 비교" value={version} onChange={event => setVersion(event.target.value as Version)} disabled={!records}>
          <option value="after">수정 후</option><option value="before">수정 전</option>
        </select>
        <button disabled={!records} onClick={() => { if (ended) { setCursor(1000); setPlaying(true); } else setPlaying(value => !value); }}>
          {ended ? "다시 재생" : playing ? "일시정지" : "재생"}
        </button>
      </div>
    </header>
    <div className="ohlc" aria-live="off">
      {last ? <><span>{elapsed(last[0])}</span><span>시 {format.format(last[1])}</span><span>고 {format.format(last[2])}</span><span>저 {format.format(last[3])}</span><span>종 {format.format(last[4])}</span></> : <span>{error || "체결 기록을 불러오는 중…"}</span>}
    </div>
    <div className="plot">{error ? <p role="alert">{error}</p> : <Chart bars={bars} reference={record?.reference ?? 10000} />}</div>
    <footer>
      <span>{elapsed(cursor)}</span>
      <input type="range" aria-label="시뮬레이션 재생 시점" aria-valuetext={elapsed(cursor)} min={1000} max={duration} step={1000} value={cursor}
        disabled={!records} onChange={event => { setCursor(Number(event.target.value)); setPlaying(false); }} />
      <span>{elapsed(duration)}</span><span className="replay-note">체결 재생 · 30배속</span>
    </footer>
  </main>;
}

createRoot(document.getElementById("root")!).render(<StrictMode><Simulation /></StrictMode>);
