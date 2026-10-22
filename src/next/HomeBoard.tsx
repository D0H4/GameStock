import { useEffect, useState } from "react";
import { GameIcon } from "../components/GameIcon";
import { HoldingsTable } from "../components/HoldingsTable";
import { Link } from "../components/Link";
import { NewsFeed } from "../components/NewsFeed";
import { PriceCell } from "../components/PriceCell";
import { apiFetch } from "../lib/api";
import { bucketPrices, type PricePoint } from "../market/dayHistory";
import { compactWon, indexValue, rate, signedNumber, since, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { holdings, sessionRate, totals } from "../market/selectors";
import type { MarketSnapshot } from "../market/types";
import { LISTING_BY_CODE } from "../market/universe";
import { MARKET_PATH, NEWS_PATH } from "../router";

/*
 * 지수 기록 API가 없어서 서버 지수와 같은 식(종목별 기준가 대비 비율을 시가총액으로 가중 평균 × 1000)을
 * 종목별 가격 기록에서 10분 간격으로 다시 계산한다. 마지막 점은 실시간 지수로 바꿔 끼운다.
 */
function useIndexHistory(snapshot: MarketSnapshot): { points: number[]; status: "loading" | "ready" | "error" } {
  const [state, setState] = useState<{ points: number[]; status: "loading" | "ready" | "error" }>({ points: [], status: "loading" });
  const codes = snapshot.codes.join(",");

  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const list = codes.split(",").filter(Boolean);
        const histories = await Promise.all(list.map((code) => apiFetch<PricePoint[]>(`/api/stocks/${code}/history?range=24h`).catch(() => [] as PricePoint[])));
        if (!active) return;
        const now = Date.now();
        const ratios = list.map((code, index) => {
          const prevClose = snapshot.quotes[code]?.prevClose ?? 0;
          const weight = snapshot.quotes[code]?.marketCap ?? 0;
          const prices = bucketPrices(histories[index], now);
          if (!prevClose || !weight || prices.length === 0) return null;
          return { weight, ratios: prices.map((price) => price / prevClose) };
        }).filter((row): row is { weight: number; ratios: number[] } => row !== null);
        if (ratios.length === 0) {
          setState({ points: [], status: "error" });
          return;
        }
        const totalWeight = ratios.reduce((sum, row) => sum + row.weight, 0);
        const points = ratios[0].ratios.map((_, index) => (1000 * ratios.reduce((sum, row) => sum + row.ratios[index] * row.weight, 0)) / totalWeight);
        setState({ points, status: "ready" });
      } catch {
        if (active) setState((current) => ({ ...current, status: current.points.length ? "ready" : "error" }));
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 60_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
    // 시세가 바뀔 때마다가 아니라 종목 구성이 바뀔 때만 다시 받는다.
  }, [codes]);

  return state;
}

function IndexChart({ points, live }: { points: number[]; live: number }) {
  const series = points.length ? [...points.slice(0, -1), live] : [];
  if (series.length < 2) return <div className="nx-index-chart is-empty" aria-hidden="true" />;
  const values = [...series, 1000];
  const max = Math.max(...values);
  const min = Math.min(...values);
  const span = max - min || 1;
  const y = (value: number) => 92 - ((value - min) / span) * 84;
  const x = (index: number) => (index / (series.length - 1)) * 100;
  const path = series.map((value, index) => `${index === 0 ? "M" : "L"} ${x(index).toFixed(2)} ${y(value).toFixed(2)}`).join(" ");
  const tone = live >= 1000 ? "is-up" : "is-down";
  return (
    <svg className={`nx-index-chart ${tone}`} viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
      <line className="nx-index-base" x1="0" x2="100" y1={y(1000)} y2={y(1000)} vectorEffect="non-scaling-stroke" />
      <path className="nx-index-line" d={path} vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/** 칸 면적은 시가총액, 색 농도는 등락 크기. 화려함이 곧 정보가 되는 자리. */
function Heatmap() {
  const snapshot = useMarket();
  const items = snapshot.codes
    .map((code) => ({ code, quote: snapshot.quotes[code], listing: LISTING_BY_CODE[code] }))
    .filter((item) => item.quote && item.listing)
    .sort((a, b) => (b.quote.marketCap ?? 0) - (a.quote.marketCap ?? 0));
  const total = items.reduce((sum, item) => sum + (item.quote.marketCap ?? 1), 0) || 1;
  return (
    <ul className="nx-heat" aria-label="종목별 등락 지도. 칸 크기는 시가총액">
      {items.map(({ code, quote, listing }) => {
        const ratio = sessionRate(quote);
        const level = Math.min(4, Math.ceil(Math.abs(ratio) * 100 / 4));
        const tone = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";
        const share = (quote.marketCap ?? 1) / total;
        return (
          <li key={code} style={{ flexGrow: Math.max(1, Math.round(share * 100)), flexBasis: `${Math.max(9, share * 100 * 2.2)}%` }}>
            <Link to={MARKET_PATH(code)} className={`nx-heat-tile is-${tone} is-l${ratio === 0 ? 0 : Math.max(1, level)}${quote.restriction ? " is-guard" : ""}`}>
              <span className="nx-heat-name">{listing.name}</span>
              <span className="nx-heat-rate nx-num">{rate(ratio)}</span>
              <span className="nx-heat-price nx-num">{won(quote.price)}</span>
              {quote.restriction && <span className="nx-heat-guard">{quote.restriction.kind === "CIRCUIT_BREAKER" ? "서킷브레이커" : "VI"}</span>}
            </Link>
          </li>
        );
      })}
    </ul>
  );
}

const MOVERS_LIMIT = 5;

function Movers() {
  const snapshot = useMarket();
  const codes = [...snapshot.codes]
    .filter((code) => snapshot.quotes[code])
    .sort((a, b) => Math.abs(sessionRate(snapshot.quotes[b])) - Math.abs(sessionRate(snapshot.quotes[a])))
    .slice(0, MOVERS_LIMIT);
  return (
    <ol className="nx-movers">
      {codes.map((code) => {
        const quote = snapshot.quotes[code];
        const listing = LISTING_BY_CODE[code];
        const ratio = sessionRate(quote);
        const news = snapshot.news.filter((item) => item.code === code).sort((a, b) => b.at - a.at)[0];
        return (
          <li key={code}>
            <Link to={MARKET_PATH(code)} className="nx-mover">
              <GameIcon code={code} size="small" />
              <span className="nx-mover-name">{listing.name}</span>
              <PriceCell className="nx-num nx-mover-price" value={quote.price} />
              <span className={`nx-num nx-mover-rate ${ratio > 0 ? "is-up" : ratio < 0 ? "is-down" : ""}`}>{rate(ratio)}</span>
            </Link>
            {news ? (
              <Link to={NEWS_PATH(news.id)} className="nx-mover-news">
                <span className="nx-mover-tag">뉴스</span>
                <span className="nx-mover-headline">{news.title}</span>
                <span className="nx-mover-when">{since(news.at, snapshot.updatedAt || Date.now())}</span>
              </Link>
            ) : <p className="nx-mover-news is-none">최근 관련 뉴스가 없어요</p>}
          </li>
        );
      })}
    </ol>
  );
}

export function HomeBoard() {
  const snapshot = useMarket();
  const history = useIndexHistory(snapshot);
  const { value, prevClose } = snapshot.index;
  const change = value - prevClose;
  const sum = totals(snapshot);
  const held = holdings(snapshot, sum.total);

  return (
    <div className="nx-home">
      <h1 className="vh">슈엔증권 홈</h1>
      <section className="nx-hero" aria-labelledby="nx-hero-title">
        <div className="nx-hero-copy">
          <h2 id="nx-hero-title" className="nx-panel-title">게임주 지수</h2>
          {/* 지수는 매 틱 바뀌어서 번쩍임이 꺼지지 않는다. 큰 숫자는 그대로 둔다. */}
          <span className="nx-num nx-hero-value">{indexValue(value)}</span>
          <p className={`nx-num nx-hero-change ${change > 0 ? "is-up" : change < 0 ? "is-down" : ""}`}>
            {signedNumber(change)} ({rate(prevClose ? change / prevClose : 0)})
          </p>
        </div>
        <figure className="nx-hero-chart">
          <IndexChart points={history.points} live={value} />
        </figure>
      </section>

      <div className="nx-home-grid">
        <section className="nx-panel nx-home-heat" aria-labelledby="nx-heat-title">
          <header className="nx-panel-head">
            <h2 id="nx-heat-title" className="nx-panel-title">시장 지도</h2>
          </header>
          <Heatmap />
        </section>
        <section className="nx-panel nx-home-movers" aria-labelledby="nx-movers-title">
          <header className="nx-panel-head">
            <h2 id="nx-movers-title" className="nx-panel-title">오늘 많이 움직인 종목</h2>
          </header>
          <Movers />
        </section>
        <section className="nx-panel nx-home-news" aria-labelledby="nx-news-title">
          <header className="nx-panel-head">
            <h2 id="nx-news-title" className="nx-panel-title">뉴스</h2>
            <Link to="/news" className="nx-more">전체 보기</Link>
          </header>
          <NewsFeed limit={6} />
        </section>
        {held.length > 0 && (
          <section className="nx-panel nx-home-holdings" aria-labelledby="nx-holdings-title">
            <header className="nx-panel-head">
              <h2 id="nx-holdings-title" className="nx-panel-title">내 보유</h2>
              <span className="nx-muted">평가액 <span className="nx-num">{compactWon(sum.stockValue)}</span></span>
            </header>
            <HoldingsTable caption="내 보유 종목과 평가 손익" />
          </section>
        )}
      </div>
    </div>
  );
}
