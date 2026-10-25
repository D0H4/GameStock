import { useRef, useState } from "react";
import { Link } from "../components/Link";
import { PriceCell } from "../components/PriceCell";
import { Sparkline } from "../components/Sparkline";
import { useHeldOrder, useHoldWhilePointing } from "../lib/useHeldOrder";
import type { EtfQuote } from "../market/derivatives";
import { rate, won } from "../market/format";
import { ETF_PATH } from "../router";
import { MarketTabs } from "./MarketTabs";

type View = "all" | "up" | "down";
type SortKey = "name" | "price" | "changePercent" | "constituents" | "prevClose";
const VIEWS: { id: View; label: string }[] = [
  { id: "all", label: "전체" }, { id: "up", label: "상승" }, { id: "down", label: "하락" },
];
const COLUMNS: { key: SortKey; label: string; className?: string }[] = [
  { key: "name", label: "종목명" },
  { key: "price", label: "현재가" },
  { key: "changePercent", label: "등락률" },
  { key: "constituents", label: "구성 종목", className: "nx-etf-col-count" },
  { key: "prevClose", label: "기준가", className: "nx-col-open" },
];

export function EtfTrend({ quote }: { quote: EtfQuote }) {
  if (quote.series.length < 2) return <span className="nx-day-note">기록 없음</span>;
  const tone = quote.changePercent > 0 ? "up" : quote.changePercent < 0 ? "down" : "flat";
  return <span className="nx-etf-trend" role="img" aria-label={`${quote.name} 최근 가격 흐름`}>
    <Sparkline series={quote.series} prevClose={quote.prevClose} tone={tone} width={140} height={36} />
  </span>;
}

/** 주식 목록과 같은 탐색 흐름. 가격·구성 종목 등 ETF에 실제로 있는 값만 표시한다. */
export function EtfBoard({ quotes, loading, error }: { quotes: EtfQuote[] | null; loading: boolean; error: string | null }) {
  const [view, setView] = useState<View>("all");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<{ key: SortKey; direction: "asc" | "desc" }>({ key: "name", direction: "asc" });
  const needle = query.trim().toLocaleLowerCase();
  const filtered = (quotes ?? []).filter(quote =>
    (view !== "up" || quote.changePercent > 0) && (view !== "down" || quote.changePercent < 0)
    && (!needle || [quote.name, quote.code, quote.theme].some(value => value.toLocaleLowerCase().includes(needle))),
  );
  filtered.sort((a, b) => {
    const comparison = sort.key === "name" ? a.name.localeCompare(b.name, "ko")
      : sort.key === "constituents" ? a.constituents.length - b.constituents.length : a[sort.key] - b[sort.key];
    return (sort.direction === "asc" ? comparison : -comparison) || a.code.localeCompare(b.code);
  });
  const [hold, holdHandlers] = useHoldWhilePointing();
  const controls = `${view}:${query}:${sort.key}:${sort.direction}`;
  const previousControls = useRef(controls);
  const holdOrder = hold && controls === previousControls.current;
  previousControls.current = controls;
  const codes = useHeldOrder(filtered.map(quote => quote.code), holdOrder);
  const byCode = new Map((quotes ?? []).map(quote => [quote.code, quote]));
  const rows = codes.flatMap(code => byCode.has(code) ? [byCode.get(code)!] : []);

  const changeSort = (key: SortKey) => setSort(current => ({
    key, direction: current.key === key ? (current.direction === "asc" ? "desc" : "asc") : key === "name" ? "asc" : "desc",
  }));

  return (
    <div className="nx-market nx-etf-market">
      <MarketTabs active="etf" />
      <header className="nx-page-head">
        {/* 주식·ETF 탭이 바로 위에 있어 제목은 화면 낭독기용으로만 둔다. */}
        <h1 className="vh">ETF</h1>
        <div className="nx-filters" role="group" aria-label="ETF 필터">
          {VIEWS.map(item => <button key={item.id} type="button" className={`nx-chip${view === item.id ? " is-active" : ""}`}
            aria-pressed={view === item.id} onClick={() => setView(item.id)}>{item.label}</button>)}
        </div>
        <label className="nx-search">
          <span className="vh">ETF 검색</span>
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5" /><path d="M16 16l4.5 4.5" /></svg>
          <input type="search" value={query} placeholder="ETF 이름, 코드, 테마" onChange={event => setQuery(event.target.value)} />
        </label>
      </header>
      {error && quotes && <p className="nx-alert" role="status">시세 갱신이 지연되어 이전 시세를 표시합니다.</p>}
      <section className="nx-panel nx-market-table" aria-label="ETF 종목 목록" aria-busy={!quotes && loading}>
        {!quotes ? <p className="nx-empty-state" role="status">{loading ? "ETF 시장을 불러오는 중…" : "ETF 시세를 불러오지 못했습니다. 잠시 후 다시 확인해 주세요."}</p>
          : quotes.length === 0 ? <p className="nx-empty-state">거래 가능한 ETF가 아직 없습니다.</p>
          : rows.length === 0 ? <div className="nx-empty-state">
            <p>조건에 맞는 ETF가 없어요.</p>
            <button type="button" className="nx-text-button" onClick={() => { setView("all"); setQuery(""); }}>필터 초기화</button>
          </div> : (
            <table className="nx-table">
              <caption className="vh">ETF 종목 시세. 열 제목을 눌러 정렬</caption>
              <thead><tr>
                {COLUMNS.map(column => {
                  const active = sort.key === column.key;
                  return <th key={column.key} scope="col" className={[column.key !== "name" && "is-num", column.className].filter(Boolean).join(" ") || undefined}
                    aria-sort={active ? (sort.direction === "asc" ? "ascending" : "descending") : "none"}>
                    <button type="button" className={`nx-sort${active ? " is-active" : ""}`} onClick={() => changeSort(column.key)}>
                      {column.label}<svg viewBox="0 0 12 12" aria-hidden="true"><path d={active && sort.direction === "asc" ? "M3 8l3-4 3 4" : "M3 4l3 4 3-4"} /></svg>
                    </button>
                  </th>;
                })}
                <th scope="col" className="nx-col-range">최근 흐름</th>
              </tr></thead>
              <tbody {...holdHandlers}>
                {rows.map(quote => <tr key={quote.code}>
                  <th scope="row"><Link to={ETF_PATH(quote.code)} className="nx-name-link">
                    <span className="nx-etf-mark" aria-hidden="true">ETF</span>
                    <span><span className="nx-name-text">{quote.name}</span><span className="nx-name-sub">{quote.code} · {quote.theme}</span></span>
                  </Link></th>
                  <td className="is-num"><PriceCell className="nx-num" value={quote.price} /></td>
                  <td className={`is-num nx-num ${quote.changePercent > 0 ? "is-up" : quote.changePercent < 0 ? "is-down" : ""}`}>
                    <span className="nx-rate-chart"><EtfTrend quote={quote} /></span>{rate(quote.changePercent / 100)}
                  </td>
                  <td className="is-num nx-num nx-etf-col-count">{quote.constituents.length}개</td>
                  <td className="is-num nx-num nx-col-open">{won(quote.prevClose)}</td>
                  <td className="nx-col-range"><EtfTrend quote={quote} /></td>
                </tr>)}
              </tbody>
            </table>
          )}
      </section>
      <p className="nx-trade-disclaimer">게임주 기반 모의 ETF 시세입니다. 현재가(NAV)로 즉시 체결됩니다.</p>
    </div>
  );
}
