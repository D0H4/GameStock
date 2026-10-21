import { useEffect, useState } from "react";
import { BrandMark } from "../components/BrandMark";
import { Delta } from "../components/Delta";
import { GameIcon } from "../components/GameIcon";
import { Link } from "../components/Link";
import { PriceCell } from "../components/PriceCell";
import { ProfileMenu } from "../components/ProfileMenu";
import { RestrictionBadge } from "../components/RestrictionBadge";
import { SeriesChart } from "../components/SeriesChart";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { clock, compactWon, indexValue, rate, shares, since, won } from "../market/format";
import { newsEffect, sessionRate, strengthRatio } from "../market/selectors";
import type { Quote, TradePrint } from "../market/types";
import { LISTING_BY_CODE } from "../market/universe";
import { NEWS_PATH, TRADE_PATH, type Route } from "../router";
import { toggleNxTheme, useNxTheme } from "./uiMode";
import { NxAccountPanel, type NxAccountTab } from "./trade/NxAccountPanel";
import { NxOrderTicket } from "./trade/NxOrderTicket";

const NAV = [
  { label: "홈", to: "/", match: "home" },
  { label: "시장", to: "/market", match: "market" },
  { label: "속보", to: "/news", match: "news" },
  { label: "랭킹", to: "/ranking", match: "ranking" },
] as const;

/** 테마와 새 화면 표시는 html에 단다. 포털로 뜨는 팝오버도 같은 색을 받아야 한다. */
export function useNxRoot(): void {
  const theme = useNxTheme();
  useEffect(() => {
    const root = document.documentElement;
    root.dataset.nx = "on";
    root.dataset.nxTheme = theme;
    root.style.colorScheme = theme;
    return () => {
      delete root.dataset.nx;
      delete root.dataset.nxTheme;
      root.style.colorScheme = "light";
    };
  }, [theme]);
}

export function NxTopbar({ route }: { route: Route }) {
  const snapshot = useMarket();
  const theme = useNxTheme();
  const { value, prevClose } = snapshot.index;
  const change = value - prevClose;
  return (
    <header className="nx-top">
      <Link className="nx-brand" to="/" aria-label="슈엔증권 홈">
        <BrandMark size={26} />
        <span>슈엔증권</span>
      </Link>
      <nav className="nx-nav" aria-label="주요 화면">
        {NAV.map((item) => {
          const active = route.name === item.match || (item.match === "market" && route.name === "trade");
          return (
            <Link key={item.to} to={item.to} className={`nx-nav-link${active ? " is-active" : ""}`} aria-current={active ? "page" : undefined}>
              {item.label}
            </Link>
          );
        })}
      </nav>
      <p className="nx-index">
        <span className="nx-index-label">게임주 지수</span>
        <span className="nx-num nx-index-value">{indexValue(value)}</span>
        <span className={`nx-num ${change > 0 ? "is-up" : change < 0 ? "is-down" : ""}`}>{rate(prevClose > 0 ? change / prevClose : 0)}</span>
      </p>
      <button type="button" className="nx-icon-button" onClick={toggleNxTheme} aria-label={theme === "dark" ? "밝은 화면으로 바꾸기" : "어두운 화면으로 바꾸기"}>
        {theme === "dark" ? (
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5" /><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8" /></svg>
        ) : (
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z" /></svg>
        )}
      </button>
      <ProfileMenu route={route} />
    </header>
  );
}

export function NxStatus() {
  return (
    <footer className="nx-status">
      <p>모의 시세입니다. 실제 시장 데이터가 아닙니다.</p>
    </footer>
  );
}

function Watchlist({ current }: { current: string }) {
  const snapshot = useMarket();
  return (
    <nav className="nx-panel nx-watch" aria-label="종목 목록">
      <h2 className="nx-panel-title">종목 <span className="nx-num">{snapshot.codes.length}</span></h2>
      <ul>
        {snapshot.codes.map((code) => {
          const listing = LISTING_BY_CODE[code];
          const quote = snapshot.quotes[code];
          if (!listing || !quote) return null;
          const ratio = sessionRate(quote);
          const active = code === current;
          return (
            <li key={code}>
              <Link to={TRADE_PATH(code)} className={`nx-watch-row${active ? " is-active" : ""}`} aria-current={active ? "page" : undefined}>
                <GameIcon code={code} size="small" />
                <span className="nx-watch-name">{listing.name}</span>
                <PriceCell className="nx-num nx-watch-price" value={quote.price} />
                <span className={`nx-num nx-watch-rate ${ratio > 0 ? "is-up" : ratio < 0 ? "is-down" : ""}`}>{rate(ratio)}</span>
                {quote.restriction && <span className="nx-watch-limit">{quote.restriction.kind === "CIRCUIT_BREAKER" ? "CB" : "VI"}</span>}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

function StockHead({ code, quote }: { code: string; quote: Quote }) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const listing = LISTING_BY_CODE[code];
  const watched = snapshot.watch.includes(code);
  return (
    <header className="nx-head">
      <div className="nx-head-id">
        <GameIcon code={code} name={listing.name} size="large" />
        <div>
          <p className="nx-head-sub"><span className="nx-code">{code}</span>{listing.publisher} · {listing.genre}</p>
          <h1 className="nx-head-name">{listing.name}</h1>
        </div>
        <button type="button" className={`nx-watch-toggle${watched ? " is-on" : ""}`} aria-pressed={watched} onClick={() => api.toggleWatch(code)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z" /></svg>
          {watched ? "관심 종목" : "관심 등록"}
        </button>
      </div>
      <div className="nx-head-price">
        <PriceCell className="nx-num nx-price" value={quote.price} />
        <Delta change={quote.price - quote.prevClose} ratio={sessionRate(quote)} className="nx-head-delta" />
        <RestrictionBadge code={code} />
      </div>
      <dl className="nx-stats">
        <div><dt>전일</dt><dd className="nx-num">{won(quote.prevClose)}</dd></div>
        <div><dt>고가</dt><dd className="nx-num is-up">{won(quote.high)}</dd></div>
        <div><dt>저가</dt><dd className="nx-num is-down">{won(quote.low)}</dd></div>
        <div><dt>거래량</dt><dd className="nx-num">{shares(quote.volume)}</dd></div>
        <div><dt>시가총액</dt><dd className="nx-num">{quote.marketCap ? compactWon(quote.marketCap) : "-"}</dd></div>
        <div><dt>상한가</dt><dd className="nx-num">{won(quote.limitUp)}</dd></div>
        <div><dt>하한가</dt><dd className="nx-num">{won(quote.limitDown)}</dd></div>
      </dl>
    </header>
  );
}

function NewsStrip({ code, quote }: { code: string; quote: Quote }) {
  const snapshot = useMarket();
  const latest = snapshot.news.filter((item) => item.code === code).sort((a, b) => b.at - a.at)[0];
  if (!latest) return <p className="nx-news is-empty">이 종목의 최근 뉴스가 아직 없어요.</p>;
  const effect = latest.priceChangeRatio ?? newsEffect(latest.priceAtPublish, quote);
  return (
    <Link to={NEWS_PATH(latest.id)} className="nx-news">
      <span className="nx-news-tag">뉴스</span>
      <span className="nx-news-title">{latest.title}</span>
      <span className="nx-news-meta">{latest.source} · {since(latest.at, snapshot.updatedAt || Date.now())}</span>
      <span className="nx-news-effect">
        <span className="nx-news-effect-label">발행 후</span>
        <span className={`nx-num ${effect > 0 ? "is-up" : effect < 0 ? "is-down" : ""}`}>{rate(effect)}</span>
      </span>
    </Link>
  );
}

const NOTE_LIMIT = 9;
/** 레인 높이(호가 5줄)에서 위쪽 레인 이름 자리를 뺀 만큼만 노트를 쌓는다. */
const LANE_ROOM = 5 * 34 - 26;

/** 같은 방향으로 이어진 최근 체결 수. 리듬게임의 콤보처럼 흐름의 쏠림을 센다. */
function combo(prints: TradePrint[]): { side: "buy" | "sell"; count: number } | null {
  const first = prints[0];
  if (!first) return null;
  let count = 0;
  for (const print of prints) {
    if (print.side !== first.side) break;
    count += 1;
  }
  return { side: first.side, count };
}

/**
 * 호가 사다리 왼쪽의 체결 레인. 새 체결은 판정선(현재가 줄)에 꽂히고
 * 앞선 체결은 위로 밀려 올라가며 옅어진다. 긴 노트는 큰 체결이다.
 */
function Highway({ quote }: { quote: Quote }) {
  const prints = quote.prints.slice(0, NOTE_LIMIT);
  const maxQty = Math.max(1, ...prints.map((print) => print.qty));
  let offset = 0;
  const notes: Array<{ print: TradePrint; index: number; height: number; bottom: number }> = [];
  for (const [index, print] of prints.entries()) {
    const height = 6 + Math.round(Math.sqrt(print.qty / maxQty) * 22);
    if (offset + height > LANE_ROOM) break;
    notes.push({ print, index, height, bottom: offset });
    offset += height + 4;
  }
  const buys = prints.filter((print) => print.side === "buy").length;
  /*
   * 같은 시각·가격·수량의 체결이 연달아 올 수 있다. 뒤(오래된 쪽)부터 번호를 매겨
   * 새 체결이 앞에 붙어도 기존 노트의 키는 그대로 두고, 새 노트만 떨어지게 한다.
   */
  const seen = new Map<string, number>();
  const keys = new Array<string>(prints.length);
  for (let index = prints.length - 1; index >= 0; index -= 1) {
    const print = prints[index];
    const base = `${print.at}-${print.price}-${print.qty}-${print.side}`;
    const count = seen.get(base) ?? 0;
    seen.set(base, count + 1);
    keys[index] = `${base}#${count}`;
  }
  return (
    <div className="nx-lanes" aria-label={`최근 체결 ${prints.length}건 중 매수 ${buys}건, 매도 ${prints.length - buys}건`} role="img">
      <span className="nx-lane-label is-buy" aria-hidden="true">매수</span>
      <span className="nx-lane-label is-sell" aria-hidden="true">매도</span>
      {notes.map(({ print, index, height, bottom }) => (
        <span
          key={keys[index]}
          className={`nx-note is-${print.side}${index === 0 ? " is-hit" : ""}`}
          style={{ height, bottom, opacity: Math.max(0.25, 1 - index * 0.09) }}
          aria-hidden="true"
        />
      ))}
    </div>
  );
}

function Book({ quote, onPriceSelect }: { quote: Quote; onPriceSelect: (price: number) => void }) {
  const asks = [...quote.asks].sort((a, b) => a.price - b.price).slice(0, 5).reverse();
  const bids = [...quote.bids].sort((a, b) => b.price - a.price).slice(0, 5);
  const depth = Math.max(1, ...asks.map((level) => level.qty), ...bids.map((level) => level.qty));
  const streak = combo(quote.prints);
  const last = quote.prints[0];
  const strength = strengthRatio(quote);

  const row = (side: "ask" | "bid", level: { price: number; qty: number } | undefined, gridRow: number) => (
    <div className={`nx-book-row is-${side}`} style={{ gridRow }} key={`${side}-${gridRow}`} role="row">
      {level ? <>
        <button type="button" className="nx-book-price nx-num" onClick={() => onPriceSelect(level.price)} aria-label={`${side === "ask" ? "매도" : "매수"} 호가 ${won(level.price)}을 주문 가격으로`}>
          {won(level.price).replace("₩", "")}
        </button>
        <span className="nx-book-qty nx-num" role="cell">
          <span className="nx-book-bar" style={{ width: `${(level.qty / depth) * 100}%` }} aria-hidden="true" />
          {level.qty.toLocaleString("ko-KR")}
        </span>
      </> : <span className="nx-book-empty" aria-hidden="true">-</span>}
    </div>
  );

  return (
    <section className="nx-panel nx-book" aria-labelledby="nx-book-title">
      <header className="nx-panel-head">
        <h2 id="nx-book-title" className="nx-panel-title">호가 · 체결</h2>
        <span className="nx-muted">체결 강도 <span className="nx-num">{Math.round(strength * 100)}%</span></span>
      </header>
      <div className="nx-ladder" role="table" aria-label="매도 5단계, 현재가, 매수 5단계">
        <Highway quote={quote} />
        {Array.from({ length: 5 }, (_, index) => row("ask", asks[index - (5 - asks.length)], index + 1))}
        <div className="nx-judge" role="row" aria-label={`현재가 ${won(quote.price)}`}>
          <PriceCell className="nx-num nx-judge-price" value={quote.price} />
          <Delta change={quote.price - quote.prevClose} ratio={sessionRate(quote)} showAmount={false} />
        </div>
        <div className="nx-combo" aria-live="off">
          {streak && streak.count > 1 ? (
            <>
              <strong className={`nx-num is-${streak.side === "buy" ? "up" : "down"}`}>{streak.count}</strong>
              <span>연속 {streak.side === "buy" ? "매수" : "매도"}</span>
            </>
          ) : <span>{last ? `${last.side === "buy" ? "매수" : "매도"} 체결` : "체결 대기"}</span>}
          {last && <span className="nx-combo-last nx-num">{clock(last.at)} · {last.qty.toLocaleString("ko-KR")}주</span>}
        </div>
        {Array.from({ length: 5 }, (_, index) => row("bid", bids[index], index + 7))}
      </div>
    </section>
  );
}

export function TradeTerminal({ ticker }: { ticker: string | null }) {
  const snapshot = useMarket();
  const { setActiveDetailCode } = useMarketApi();
  const [accountTab, setAccountTab] = useState<NxAccountTab>("holdings");
  const [selected, setSelected] = useState<{ code: string; price: number; revision: number } | null>(null);
  const code = ticker?.toUpperCase() ?? snapshot.codes[0] ?? null;
  const listing = code ? LISTING_BY_CODE[code] : undefined;
  const quote = code ? snapshot.quotes[code] : undefined;

  useEffect(() => {
    if (!code) return undefined;
    setActiveDetailCode(code);
    return () => setActiveDetailCode(null);
  }, [setActiveDetailCode, code]);

  if (!code || !listing) {
    return <p className="nx-empty">거래할 종목을 찾을 수 없어요. <Link to="/market">시장으로 돌아가기</Link></p>;
  }
  if (!quote) {
    return <p className="nx-empty" role="status">시세를 불러오는 중이에요.</p>;
  }

  const markers = snapshot.news.filter((item) => item.code === code).map((item) => ({ at: item.at, label: item.title }));

  return (
    <div className="nx-terminal">
      {snapshot.portfolioError && <p role="status" className="nx-alert">{snapshot.portfolioError}</p>}
      <Watchlist current={code} />
      <section className="nx-panel nx-center" aria-label={`${listing.name} 시세와 차트`}>
        <StockHead code={code} quote={quote} />
        <div className="nx-chart integration-tools">
          <SeriesChart code={code} markers={markers} />
        </div>
        <NewsStrip code={code} quote={quote} />
      </section>
      <Book quote={quote} onPriceSelect={(price) => setSelected((previous) => ({ code, price, revision: (previous?.revision ?? 0) + 1 }))} />
      <section className="nx-panel nx-account" aria-label="내 잔고와 주문 내역">
        <NxAccountPanel code={code} tab={accountTab} onTabChange={setAccountTab}
          onFocusTicket={() => { const ticket = document.querySelector<HTMLFormElement>(".nx-ticket"); ticket?.focus({ preventScroll: true }); ticket?.scrollIntoView({ block: "nearest" }); }} />
      </section>
      <aside className="nx-panel nx-order" aria-label={`${listing.name} 매수·매도 주문`}>
        <NxOrderTicket
          key={code}
          code={code}
          onShowOrders={() => { setAccountTab("orders"); document.querySelector(".nx-account")?.scrollIntoView({ block: "nearest" }); }}
          selectedLimitPrice={selected?.code === code ? selected.price : null}
          selectedLimitPriceRevision={selected?.code === code ? selected.revision : undefined}
        />
      </aside>
    </div>
  );
}
