import { useState } from "react";
import { GameIcon } from "../components/GameIcon";
import { Link } from "../components/Link";
import { PriceCell } from "../components/PriceCell";
import { useHeldOrder, useHoldWhilePointing } from "../lib/useHeldOrder";
import { compactWon, rate, shares, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { sessionRate, sortCodes, type SortDirection, type SortKey } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { MARKET_PATH } from "../router";

type View = "all" | "up" | "down" | "watch";

const VIEWS: { id: View; label: string }[] = [
  { id: "all", label: "전체" },
  { id: "up", label: "상승" },
  { id: "down", label: "하락" },
  { id: "watch", label: "관심" },
];

const COLUMNS: { key: SortKey; label: string; numeric: boolean }[] = [
  { key: "name", label: "종목", numeric: false },
  { key: "price", label: "현재가", numeric: true },
  { key: "rate", label: "등락률", numeric: true },
  { key: "volume", label: "거래량", numeric: true },
];

/** 오늘 저가~고가 사이에서 현재가가 어디쯤인지. 하한가~상한가 폭 위에 그린다. */
function DayRange({ low, high, price, limitDown, limitUp }: { low: number; high: number; price: number; limitDown: number; limitUp: number }) {
  const span = Math.max(1, limitUp - limitDown);
  const at = (value: number) => `${Math.min(100, Math.max(0, ((value - limitDown) / span) * 100))}%`;
  return (
    <span className="nx-range" aria-label={`오늘 저가 ${won(low)}, 고가 ${won(high)}, 현재가 ${won(price)}`} role="img">
      <span className="nx-range-band" style={{ left: at(low), right: `calc(100% - ${at(high)})` }} />
      <span className="nx-range-now" style={{ left: at(price) }} />
    </span>
  );
}

export function MarketBoard() {
  const snapshot = useMarket();
  const api = useMarketApi();
  const [view, setView] = useState<View>("all");
  const [query, setQuery] = useState("");
  const [sort, setSort] = useState<{ key: SortKey; direction: SortDirection }>({ key: "volume", direction: "desc" });

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
      <header className="nx-page-head">
        <h1 className="nx-page-title">시장</h1>
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
                    <th key={column.key} scope="col" className={column.numeric ? "is-num" : undefined} aria-sort={active ? (sort.direction === "asc" ? "ascending" : "descending") : "none"}>
                      <button type="button" className={`nx-sort${active ? " is-active" : ""}`} onClick={() => changeSort(column.key)}>
                        {column.label}
                        <svg viewBox="0 0 12 12" aria-hidden="true"><path d={active && sort.direction === "asc" ? "M3 7.5L6 4.5l3 3" : "M3 4.5L6 7.5l3-3"} /></svg>
                      </button>
                    </th>
                  );
                })}
                <th scope="col" className="is-num nx-col-cap">시가총액</th>
                <th scope="col" className="nx-col-range">오늘 범위</th>
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
                    <td className={`is-num nx-num ${ratio > 0 ? "is-up" : ratio < 0 ? "is-down" : ""}`}>{rate(ratio)}</td>
                    <td className="is-num nx-num">{shares(quote.volume)}</td>
                    <td className="is-num nx-num nx-col-cap">{quote.marketCap ? compactWon(quote.marketCap) : "-"}</td>
                    <td className="nx-col-range"><DayRange low={quote.low} high={quote.high} price={quote.price} limitDown={quote.limitDown} limitUp={quote.limitUp} /></td>
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
