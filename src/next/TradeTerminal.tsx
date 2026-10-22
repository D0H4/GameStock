import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { BrandMark } from "../components/BrandMark";
import { Delta } from "../components/Delta";
import { GameIcon } from "../components/GameIcon";
import { Link } from "../components/Link";
import { PriceCell } from "../components/PriceCell";
import { ProfileMenu } from "../components/ProfileMenu";
import { RestrictionBadge } from "../components/RestrictionBadge";
import { SeriesChart } from "../components/SeriesChart";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { indexValue, rate, shares, since, won } from "../market/format";
import { marketOpinion, newsEffect, sessionRate, strengthRatio, type OpinionTone } from "../market/selectors";
import type { Quote, TradePrint } from "../market/types";
import { LISTING_BY_CODE } from "../market/universe";
import { MARKET_PATH, NEWS_PATH, navigate, type Route } from "../router";
import { toggleNxTheme, useNxTheme } from "./uiMode";
import { NxAccountPanel, type NxAccountTab } from "./trade/NxAccountPanel";
import { NxOrderTicket } from "./trade/NxOrderTicket";
import { OrderDock } from "./trade/OrderDock";
import { MarketTabs } from "./MarketTabs";

const NAV = [
  { label: "홈", to: "/", match: "home" },
  { label: "주식", to: "/market", match: "market" },
  { label: "파생", to: "/derivatives", match: "derivatives" },
  { label: "뉴스", to: "/news", match: "news" },
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

/** 종목 하나가 띠를 한 칸 지나가는 시간(초). 종목 수에 곱해 전체 한 바퀴 시간을 정한다. */
const TAPE_SECONDS_PER_ITEM = 4;

/** 상단 바 시세 자리. 게임주 지수는 왼쪽에 고정하고, 전 종목 시세는 그 옆 띠로 흘려 보낸다. */
function TopTicker() {
  const snapshot = useMarket();
  const codes = snapshot.codes.filter((code) => snapshot.quotes[code] && LISTING_BY_CODE[code]);
  const { value, prevClose } = snapshot.index;
  const change = value - prevClose;

  /* 띠가 끊기지 않게 같은 목록을 두 번 잇는다. 두 번째 줄은 화면 읽기와 탭 이동에서 뺀다. */
  const items = (copy: boolean) => codes.map((code) => {
    const quote = snapshot.quotes[code];
    const ratio = sessionRate(quote);
    return (
      <li key={code}>
        <Link className="nx-tape-item" to={MARKET_PATH(code)} tabIndex={copy ? -1 : undefined}>
          <span className="nx-tape-name">{LISTING_BY_CODE[code].name}</span>
          <span className="nx-num nx-tape-price">{won(quote.price)}</span>
          <span className={`nx-num nx-tape-rate ${ratio > 0 ? "is-up" : ratio < 0 ? "is-down" : ""}`}>{rate(ratio)}</span>
        </Link>
      </li>
    );
  });

  return (
    <div className="nx-index">
      <p className="nx-index-item">
        <span className="nx-index-label">게임주 지수</span>
        <span className="nx-num nx-index-value">{indexValue(value)}</span>
        <span className={`nx-num ${change > 0 ? "is-up" : change < 0 ? "is-down" : ""}`}>{rate(prevClose > 0 ? change / prevClose : 0)}</span>
      </p>
      {codes.length > 0 && (
        <div className="nx-tape" style={{ "--nx-tape-duration": `${codes.length * TAPE_SECONDS_PER_ITEM}s` } as CSSProperties}>
          <div className="nx-tape-track">
            <ul aria-label="종목별 현재 시세">{items(false)}</ul>
            <ul aria-hidden="true">{items(true)}</ul>
          </div>
        </div>
      )}
    </div>
  );
}

function navActive(route: Route, match: (typeof NAV)[number]["match"]): boolean {
  return route.name === match || (match === "market" && (route.name === "trade" || route.name === "etf"));
}

export function NxTopbar({ route }: { route: Route }) {
  const theme = useNxTheme();
  return (
    <header className="nx-top">
      <Link className="nx-brand" to="/" aria-label="슈엔증권 홈">
        <BrandMark size={26} />
        <span>슈엔증권</span>
      </Link>
      <nav className="nx-nav" aria-label="주요 화면">
        {NAV.map((item) => {
          const active = navActive(route, item.match);
          return (
            <Link key={item.to} to={item.to} className={`nx-nav-link${active ? " is-active" : ""}`} aria-current={active ? "page" : undefined}>
              {item.label}
            </Link>
          );
        })}
      </nav>
      <button type="button" className="nx-icon-button" onClick={toggleNxTheme} aria-label={theme === "dark" ? "밝은 화면으로 바꾸기" : "어두운 화면으로 바꾸기"}>
        {theme === "dark" ? (
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4.5" /><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8" /></svg>
        ) : (
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z" /></svg>
        )}
      </button>
      <TopTicker />
      <ProfileMenu route={route} />
    </header>
  );
}

/**
 * 휴대폰 브라우저의 아래 탭바. 본문 뒤에 두어 Tab 이동도 보이는 순서(위 → 본문 → 아래)를 따른다.
 * 다른 화면에서는 CSS로 숨기고, 그때는 상단 바의 메뉴가 같은 일을 한다.
 */
export function NxTabbar({ route }: { route: Route }) {
  return (
    <nav className="nx-tabbar" aria-label="주요 화면">
      {NAV.map((item) => {
        const active = navActive(route, item.match);
        return (
          <Link key={item.to} to={item.to} className={`nx-tabbar-link${active ? " is-active" : ""}`} aria-current={active ? "page" : undefined}>
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}

function StockSelector({ current }: { current: string }) {
  const snapshot = useMarket();
  return (
    <label className="nx-stock-selector">
      <span>종목 선택</span>
      <select value={current} onChange={(event) => navigate(MARKET_PATH(event.target.value))}>
        {snapshot.codes.map((code) => {
          const listing = LISTING_BY_CODE[code];
          return listing ? <option key={code} value={code}>{listing.name} · {code}{snapshot.watch.includes(code) ? " · 관심" : ""}</option> : null;
        })}
      </select>
    </label>
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
        <button type="button" className={`nx-watch-toggle${watched ? " is-on" : ""}`} aria-label={`${listing.name} 관심 ${watched ? "해제" : "등록"}`} aria-pressed={watched} onClick={() => api.toggleWatch(code)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8-5.2-2.8-5.2 2.8 1-5.8-4.3-4.1 5.9-.9z" /></svg>
          {watched ? "관심 종목" : "관심 등록"}
        </button>
      </div>
      <div className="nx-head-price">
        <PriceCell className="nx-num nx-price" value={quote.price} />
        <Delta change={quote.price - quote.prevClose} ratio={sessionRate(quote)} className="nx-head-delta" />
        <RestrictionBadge code={code} />
      </div>
    </header>
  );
}

function StockStats({ quote }: { quote: Quote }) {
  return (
    <section className="nx-trade-facts" aria-labelledby="nx-stock-info-title">
      <h2 id="nx-stock-info-title">종목 정보</h2>
      <dl className="nx-stats">
        <div><dt>전일</dt><dd className="nx-num">{won(quote.prevClose)}</dd></div>
        <div><dt>시가</dt><dd className="nx-num">{won(quote.open)}</dd></div>
        <OpinionStat quote={quote} />
        <div><dt>고가</dt><dd className="nx-num is-up">{won(quote.high)}</dd></div>
        <div><dt>저가</dt><dd className="nx-num is-down">{won(quote.low)}</dd></div>
        <div><dt>상한가</dt><dd className="nx-num">{won(quote.limitUp)}</dd></div>
        <div><dt>하한가</dt><dd className="nx-num">{won(quote.limitDown)}</dd></div>
        <div><dt>거래량</dt><dd className="nx-num">{shares(quote.volume)}</dd></div>
      </dl>
    </section>
  );
}

const OPINION_LABEL: Record<OpinionTone, string> = {
  buy: "매수 우위",
  sell: "매도 우위",
  neutral: "중립",
  caution: "변동성 주의",
};

const signalScore = (score: number) => (score > 0 ? "+1" : score < 0 ? "-1" : "0");

/** 시세 칸의 시장 의견. 누르거나 마우스를 올리면 어떤 신호로 나온 값인지 펼친다. */
function OpinionStat({ quote }: { quote: Quote }) {
  const snapshot = useMarket();
  const id = useId();
  const opinion = marketOpinion(quote, snapshot.news, snapshot.updatedAt || Date.now());
  const goodNews = (opinion.newsCount + opinion.newsBalance) / 2;
  const badNews = opinion.newsCount - goodNews;
  return (
    <div className="nx-opinion">
      <dt>의견</dt>
      <dd>
        <button type="button" className={`nx-opinion-value is-${opinion.tone}`} aria-describedby={id}
          onKeyDown={(event) => { if (event.key === "Escape") event.currentTarget.blur(); }}>
          {OPINION_LABEL[opinion.tone]}
        </button>
        <div className="nx-opinion-panel" id={id} role="tooltip">
          {opinion.caution && <p className="nx-opinion-caution">{opinion.caution}: 가격이 크게 흔들릴 수 있어 의견을 내지 않아요.</p>}
          <table>
            <tbody>
              <tr><th scope="row">최근 체결 <span className="nx-num">{opinion.printCount}</span>건</th><td>매수 <span className="nx-num">{Math.round(opinion.buyShare * 100)}%</span></td><td className="nx-num">{signalScore(opinion.strengthScore)}</td></tr>
              <tr><th scope="row">시가 대비</th><td className="nx-num">{rate(opinion.fromOpen)}</td><td className="nx-num">{signalScore(opinion.openScore)}</td></tr>
              <tr><th scope="row">24시간 뉴스</th><td>호재 <span className="nx-num">{goodNews}</span> · 악재 <span className="nx-num">{badNews}</span></td><td className="nx-num">{signalScore(opinion.newsScore)}</td></tr>
            </tbody>
          </table>
          <p className="nx-opinion-rule">합이 +2 이상이면 매수 우위, -2 이하면 매도 우위예요. 지금 거래 흐름을 요약한 값이고, 앞으로의 가격을 알려 주지는 않아요.</p>
        </div>
      </dd>
    </div>
  );
}

function StockNews({ code, quote }: { code: string; quote: Quote }) {
  const snapshot = useMarket();
  const items = useMemo(() => snapshot.news.filter((item) => item.code === code).sort((a, b) => b.at - a.at).slice(0, 4), [snapshot.news, code]);
  return (
    <section className="nx-stock-news" aria-labelledby="nx-stock-news-title">
      <header><h2 id="nx-stock-news-title">관련 뉴스</h2><Link to="/news">전체 뉴스</Link></header>
      {items.length === 0 ? <p className="nx-muted">이 종목의 최근 뉴스가 아직 없어요.</p> : items.map((item) => {
        const effect = item.priceChangeRatio ?? newsEffect(item.priceAtPublish, quote);
        return <Link key={item.id} to={NEWS_PATH(item.id)} className="nx-stock-article">
          <span>{item.source} · {since(item.at, snapshot.updatedAt || Date.now())}</span>
          <h3>{item.title}</h3>
          <span>발행 후 <strong className={`nx-num ${effect > 0 ? "is-up" : effect < 0 ? "is-down" : ""}`}>{rate(effect)}</strong></span>
        </Link>;
      })}
    </section>
  );
}

const NOTE_LIMIT = 9;
/** 레인 높이(호가 5줄)에서 위쪽 레인 이름 자리를 뺀 만큼만 노트를 쌓는다. */
const LANE_HEIGHT = 5 * 34;
const LANE_ROOM = LANE_HEIGHT - 26;

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

type Arrival = { code: string; signature: string; fresh: Set<string>; rise: number };

const RISE_MS = 260;
const RISE_EASING = "cubic-bezier(0.16, 1, 0.3, 1)";

/**
 * 호가 사다리 왼쪽의 체결 레인. 새 체결은 판정선(현재가 줄) 아래에서 한 묶음으로 올라오고
 * 앞선 체결은 같은 거리만큼 위로 밀려 올라가며 옅어진다. 긴 노트는 큰 체결이다.
 */
function Highway({ quote }: { quote: Quote }) {
  const arrival = useRef<Arrival | null>(null);
  const stack = useRef<HTMLDivElement>(null);
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
  /*
   * 체결은 몇 초마다 여러 건이 한꺼번에 온다. 새로 온 노트 전체를 앞선 노트가 밀려나는
   * 거리(rise)만큼 아래에서 같이 올려야 같은 줄 노트끼리 서로를 가로지르지 않는다.
   * 체결 목록이 그대로인 다시 그리기에서는 앞서 정한 묶음을 유지해 진행 중인 움직임을 끊지 않는다.
   */
  const signature = keys.join(",");
  const previous = arrival.current;
  if (!previous || previous.code !== quote.code) {
    arrival.current = { code: quote.code, signature, fresh: new Set(), rise: 0 };
  } else if (previous.signature !== signature) {
    const known = new Set(previous.signature.split(","));
    const fresh = new Set(keys.filter((key) => !known.has(key)));
    const firstKept = notes.find(({ index }) => !fresh.has(keys[index]));
    arrival.current = { code: quote.code, signature, fresh, rise: firstKept ? firstKept.bottom : offset };
  }
  const current = arrival.current!;
  const { fresh, rise } = current;
  /*
   * 노트를 하나씩 따로 움직이면 새 노트와 밀려나는 노트의 움직임이 한 프레임만 어긋나도
   * 같은 줄에서 서로 겹친다. 노트는 최종 자리에 바로 두고, 노트를 담은 판 전체를
   * rise만큼 아래에서 한 번에 올린다. 판 안에서 노트끼리의 간격은 움직이는 동안에도 그대로다.
   * 앞 묶음이 아직 올라오는 중이면 남은 거리를 이어받아 튀지 않게 한다.
   * 숨은 탭에서는 애니메이션 시간이 멈춰 남은 거리가 계속 쌓이므로 레인 높이에서 자른다.
   */
  useLayoutEffect(() => {
    const element = stack.current;
    if (!element) return;
    if (rise <= 0 || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      for (const running of element.getAnimations()) running.cancel();
      return;
    }
    const transform = getComputedStyle(element).transform;
    const carried = transform === "none" ? 0 : new DOMMatrixReadOnly(transform).m42;
    for (const running of element.getAnimations()) running.cancel();
    element.animate(
      [{ transform: `translateY(${Math.min(rise + carried, LANE_HEIGHT)}px)` }, { transform: "translateY(0)" }],
      { duration: RISE_MS, easing: RISE_EASING },
    );
  }, [current, rise]);
  return (
    <div className="nx-lanes" aria-label={`최근 체결 ${prints.length}건 중 매수 ${buys}건, 매도 ${prints.length - buys}건`} role="img">
      <span className="nx-lane-label is-buy" aria-hidden="true">매수</span>
      <span className="nx-lane-label is-sell" aria-hidden="true">매도</span>
      <div className="nx-print-stack" ref={stack} aria-hidden="true">
        {notes.map(({ print, index, height, bottom }) => (
          <span
            key={keys[index]}
            className={`nx-print is-${print.side}${index === 0 && fresh.has(keys[index]) ? " is-hit" : ""}`}
            style={{ height, bottom, opacity: Math.max(0.25, 1 - index * 0.09) }}
          />
        ))}
      </div>
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
          {level.qty.toLocaleString("ko-KR")}<span className="nx-book-unit">주</span>
        </span>
      </> : <span className="nx-book-empty" aria-hidden="true">-</span>}
    </div>
  );

  return (
    <section className="nx-panel nx-book" aria-labelledby="nx-book-title">
      <header className="nx-panel-head">
        <h2 id="nx-book-title" className="nx-panel-title">호가 · 체결</h2>
        <span className="nx-muted" title={`최근 체결 ${quote.prints.length}건 기준`}>체결 강도 <span className="nx-num">{strength === null ? "-" : `${Math.round(strength * 100)}%`}</span></span>
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
  const [orderOpen, setOrderOpen] = useState(false);
  const accountFocusPending = useRef(false);
  const code = ticker?.toUpperCase() ?? snapshot.codes[0] ?? null;
  const listing = code ? LISTING_BY_CODE[code] : undefined;
  const quote = code ? snapshot.quotes[code] : undefined;
  const markers = useMemo(() => snapshot.news.filter((item) => item.code === code).map((item) => ({ at: item.at, label: item.title })), [snapshot.news, code]);

  useEffect(() => { setOrderOpen(false); }, [code]);

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

  const focusTicket = () => {
    if (window.matchMedia("(max-width: 960px)").matches) setOrderOpen(true);
    else {
      const ticket = document.querySelector<HTMLFormElement>(".nx-ticket");
      ticket?.focus({ preventScroll: true });
      ticket?.scrollIntoView({ block: "nearest" });
    }
  };
  const focusAccount = () => {
    const account = document.querySelector<HTMLElement>(".nx-account");
    account?.focus({ preventScroll: true });
    account?.scrollIntoView({ block: "nearest" });
    accountFocusPending.current = false;
  };

  return (
    <div className="nx-trade-page">
      <MarketTabs active="stocks" />
      <div className="nx-trade-toolbar">
        <Link to="/market" className="nx-back-link"><span aria-hidden="true">←</span> 주식 목록</Link>
        <StockSelector current={code} />
      </div>
      <div className="nx-workspace">
        {snapshot.portfolioError && <p role="status" className="nx-alert">{snapshot.portfolioError}</p>}
        <div className="nx-trade-main">
        <section className="nx-trade-chart" aria-label={`${listing.name} 시세와 차트`}>
          <StockHead code={code} quote={quote} />
          <div className="nx-chart integration-tools">
            <SeriesChart key={code} code={code} markers={markers} initialMode="candle" />
          </div>
        </section>
        <StockStats quote={quote} />
        <section className="nx-panel nx-account" aria-label="내 잔고와 주문 내역" tabIndex={-1}>
          <NxAccountPanel code={code} tab={accountTab} onTabChange={setAccountTab}
            onFocusTicket={focusTicket} />
        </section>
        <div className="nx-trade-details">
          <Book quote={quote} onPriceSelect={(price) => { setSelected((previous) => ({ code, price, revision: (previous?.revision ?? 0) + 1 })); focusTicket(); }} />
          <StockNews code={code} quote={quote} />
        </div>
        <p className="nx-trade-disclaimer">게임 뉴스와 모의 주문으로 형성되는 시세입니다. 실제 금융상품이나 투자 수익과 관계없습니다.</p>
        </div>
        <OrderDock title={listing.name} quote={won(quote.price)} open={orderOpen} onOpenChange={setOrderOpen}
          onAfterClose={() => { if (accountFocusPending.current) focusAccount(); }}>
          <NxOrderTicket
            key={code}
            code={code}
            onShowOrders={() => {
              setAccountTab("orders");
              if (orderOpen) accountFocusPending.current = true;
              else window.requestAnimationFrame(focusAccount);
              setOrderOpen(false);
            }}
            selectedLimitPrice={selected?.code === code ? selected.price : null}
            selectedLimitPriceRevision={selected?.code === code ? selected.revision : undefined}
          />
        </OrderDock>
      </div>
    </div>
  );
}
