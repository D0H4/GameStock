import { useEffect, useState } from "react";
import { GameIcon } from "../components/GameIcon";
import { Link } from "../components/Link";
import { PriceCell } from "../components/PriceCell";
import { apiFetch } from "../lib/api";
import { useHeldOrder, useHoldWhilePointing } from "../lib/useHeldOrder";
import { bucketPrices, type PricePoint } from "../market/dayHistory";
import { rate, shares, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate, sortCodes, type SortDirection, type SortKey } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { MARKET_PATH } from "../router";
import { MarketTabs } from "./MarketTabs";

type View = "all" | "up" | "down" | "watch";

const VIEWS: { id: View; label: string }[] = [
  { id: "all", label: "전체" },
  { id: "up", label: "상승" },
  { id: "down", label: "하락" },
  { id: "watch", label: "관심" },
];

const COLUMNS: { key: SortKey; label: string; numeric: boolean; className?: string }[] = [
  { key: "name", label: "종목", numeric: false },
  { key: "price", label: "현재가", numeric: true },
  { key: "rate", label: "등락률", numeric: true },
  { key: "volume", label: "거래량", numeric: true },
  { key: "open", label: "시가", numeric: true, className: "nx-col-open" },
];

/** 종목별 10분 칸 가격. 키가 없으면 아직 불러오는 중, null이면 불러오지 못한 것. */
type DayHistories = Record<string, number[] | null>;

/* 종목별 오늘 가격 기록을 10분 칸으로 받아 둔다. 시세가 바뀔 때마다가 아니라 1분마다, 종목 구성이 바뀔 때 다시 받는다. */
function useDayHistories(codes: string[]): DayHistories {
  const [histories, setHistories] = useState<DayHistories>({});
  const key = codes.join(",");

  useEffect(() => {
    let active = true;
    const load = async () => {
      const list = key.split(",").filter(Boolean);
      const rows = await Promise.all(list.map((code) => apiFetch<PricePoint[]>(`/api/stocks/${code}/history?range=24h`).catch(() => null)));
      if (!active) return;
      const now = Date.now();
      // 다시 받다가 실패한 종목은 앞서 받아 둔 그래프를 그대로 둔다.
      setHistories((previous) => Object.fromEntries(list.map((code, index) => {
        const row = rows[index];
        return [code, row ? bucketPrices(row, now) : previous[code] ?? null];
      })));
    };
    void load();
    const timer = window.setInterval(() => void load(), 60_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [key]);

  return histories;
}

/** 오늘 0시부터 지금까지의 가격 흐름. 점선은 전일 종가, 마지막 점은 실시간 현재가. */
function DayChart({ points, price, prevClose, low, high }: { points: number[] | null | undefined; price: number; prevClose: number; low: number; high: number }) {
  const label = `오늘 저가 ${won(low)}, 고가 ${won(high)}, 현재가 ${won(price)}`;
  if (points === undefined) return <span className="nx-day-chart is-loading" role="img" aria-label={`오늘 흐름을 불러오는 중. ${label}`} />;
  if (points === null) return <span className="nx-day-note">못 불러옴<span className="vh">. {label}</span></span>;
  // 0시 직후처럼 칸이 하나뿐이어도 그 칸에서 현재가까지 한 줄은 그린다.
  const series = points.length > 1 ? [...points.slice(0, -1), price] : points.length === 1 ? [points[0], price] : [];
  if (series.length < 2) return <span className="nx-day-note">기록 없음<span className="vh">. {label}</span></span>;
  const values = [...series, prevClose];
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || 1;
  const y = (value: number) => 34 - ((value - min) / span) * 30;
  const x = (index: number) => (index / (series.length - 1)) * 140;
  const path = series.map((value, index) => `${index === 0 ? "M" : "L"} ${x(index).toFixed(1)} ${y(value).toFixed(1)}`).join(" ");
  const tone = price > prevClose ? "is-up" : price < prevClose ? "is-down" : "is-flat";
  return (
    <svg className={`nx-day-chart ${tone}`} viewBox="0 0 140 36" preserveAspectRatio="none" role="img" aria-label={label}>
      <line className="nx-day-base" x1="0" x2="140" y1={y(prevClose)} y2={y(prevClose)} vectorEffect="non-scaling-stroke" />
      <path className="nx-day-line" d={path} vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

export function MarketBoard() {
  const snapshot = useMarket();
  const api = useMarketApi();
  const [view, setView] = useState<View>("all");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<{ key: SortKey; direction: SortDirection }>({ key: "volume", direction: "desc" });
  const histories = useDayHistories(snapshot.codes);

  const matches = (code: string): boolean => {
    const quote = snapshot.quotes[code];
    const listing = LISTING_BY_CODE[code];
    if (!quote || !listing) return false;
    if (view === "up" && sessionRate(quote) <= 0) return false;
    if (view === "down" && sessionRate(quote) >= 0) return false;
    if (view === "watch" && !snapshot.watch.includes(code)) return false;
    const needle = query.trim().toLowerCase();
    return !needle || [listing.name, listing.code, listing.publisher].some((field) => field.toLowerCase().includes(needle));
  };

  /* 가리키는 동안에는 줄 순서를 붙잡아 둔다. 누르려던 줄이 시세 갱신으로 도망가지 않게. */
  const [hold, holdHandlers] = useHoldWhilePointing();
  const codes = useHeldOrder(sortCodes(snapshot, sort.key, sort.direction).filter(matches), hold);

  const changeSort = (key: SortKey) =>
    setSort((current) => current.key === key
      ? { key, direction: current.direction === "desc" ? "asc" : "desc" }
      : { key, direction: key === "name" ? "asc" : "desc" });

  return (
    <div className="nx-market">
      <MarketTabs active="stocks" />
      <header className="nx-page-head">
        <h1 className="nx-page-title">주식</h1>
        <div className="nx-filters" role="group" aria-label="종목 필터">
          {VIEWS.map((item) => (
            <button key={item.id} type="button" className={`nx-chip${view === item.id ? " is-active" : ""}`} aria-pressed={view === item.id} onClick={() => setView(item.id)}>
              {item.label}
            </button>
          ))}
        </div>
        <label className="nx-search">
          <span className="vh">종목 검색</span>
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /></svg>
          <input type="search" value={query} placeholder="게임 이름, 코드, 퍼블리셔" onChange={(event) => setQuery(event.target.value)} />
        </label>
      </header>

      <section className="nx-panel nx-market-table" {...holdHandlers}>
        {codes.length === 0 ? (
          <div className="nx-empty-state">
            <p>조건에 맞는 종목이 없어요.</p>
            <button type="button" className="nx-text-button" onClick={() => { setView("all"); setQuery(""); }}>필터 초기화</button>
          </div>
        ) : (
          <table className="nx-table">
            <caption className="vh">상장 종목 시세. 열 제목을 눌러 정렬</caption>
            <thead>
              <tr>
                <th scope="col" className="nx-col-watch"><span className="vh">관심</span></th>
                {COLUMNS.map((column) => {
                  const active = sort.key === column.key;
                  return (
                    <th key={column.key} scope="col" className={[column.numeric && "is-num", column.className].filter(Boolean).join(" ") || undefined} aria-sort={active ? (sort.direction === "asc" ? "ascending" : "descending") : "none"}>
                      <button type="button" className={`nx-sort${active ? " is-active" : ""}`} onClick={() => changeSort(column.key)}>
                        {column.label}
                        <svg viewBox="0 0 12 12" aria-hidden="true"><path d={active && sort.direction === "asc" ? "M3 7.5L6 4.5l3 3" : "M3 4.5L6 7.5l3-3"} /></svg>
                      </button>
                    </th>
                  );
                })}
                <th scope="col" className="nx-col-range">오늘 흐름</th>
              </tr>
            </thead>
            <tbody>
              {codes.map((code) => {
                const quote = snapshot.quotes[code];
                const listing = LISTING_BY_CODE[code];
                const ratio = sessionRate(quote);
                const watched = snapshot.watch.includes(code);
                return (
                  <tr key={code}>
                    <td className="nx-col-watch">
                      <button type="button" className={`nx-star${watched ? " is-on" : ""}`} aria-pressed={watched} aria-label={`${listing.name} 관심 ${watched ? "해제" : "등록"}`} onClick={() => api.toggleWatch(code)}>
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z" /></svg>
                      </button>
                    </td>
                    <th scope="row">
                      <Link to={MARKET_PATH(code)} className="nx-name-link">
                        <GameIcon code={code} size="small" />
                        <span>
                          <span className="nx-name-text">{listing.name}</span>
                          <span className="nx-name-sub">{code} · {listing.publisher}</span>
                        </span>
                        {quote.restriction && <span className="nx-watch-limit is-inline">{quote.restriction.kind === "CIRCUIT_BREAKER" ? "CB" : "VI"}</span>}
                      </Link>
                    </th>
                    <td className="is-num"><PriceCell className="nx-num" value={quote.price} /></td>
                    <td className={`is-num nx-num ${ratio > 0 ? "is-up" : ratio < 0 ? "is-down" : ""}`}>
                      {/* 휴대폰 폭에서는 그래프 열을 따로 둘 자리가 없어 등락률 위에 작게 얹는다. 넓은 화면에서는 숨긴다. */}
                      <span className="nx-rate-chart"><DayChart points={histories[code]} price={quote.price} prevClose={quote.prevClose} low={quote.low} high={quote.high} /></span>
                      {rate(ratio)}
                    </td>
                    <td className="is-num nx-num">{shares(quote.volume)}</td>
                    <td className="is-num nx-num nx-col-open">{won(quote.open)}</td>
                    <td className="nx-col-range"><DayChart points={histories[code]} price={quote.price} prevClose={quote.prevClose} low={quote.low} high={quote.high} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
