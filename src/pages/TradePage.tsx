import { useEffect, useState } from "react";
import { AdvancedOrderTicket } from "../components/AdvancedOrderTicket";
import { Delta } from "../components/Delta";
import { GameIcon } from "../components/GameIcon";
import { Link } from "../components/Link";
import { OrderBook } from "../components/OrderBook";
import { PriceCell } from "../components/PriceCell";
import { TradeAccountPanel, type AccountTab } from "../components/TradeAccountPanel";
import { StockChartPanel } from "../components/StockChartPanel";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { compactWon, shares, won } from "../market/format";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { MARKET_PATH } from "../router";

export function TradePage({ ticker }: { ticker: string | null }) {
  const snapshot = useMarket();
  const [accountTab, setAccountTab] = useState<AccountTab>("holdings");
  const api = useMarketApi();
  const { setActiveDetailCode } = api;
  const code = ticker?.toUpperCase() ?? null;
  const listing = code ? LISTING_BY_CODE[code] : undefined;
  const quote = code ? snapshot.quotes[code] : undefined;
  const [selectedQuote, setSelectedQuote] = useState<{ code: string; price: number; revision: number } | null>(null);

  useEffect(() => {
    if (!code) return undefined;
    setActiveDetailCode(code);
    return () => setActiveDetailCode(null);
  }, [setActiveDetailCode, code]);

  if (!code || !listing || !quote) {
    return (
      <div className="page-stack is-narrow">
        <p className="empty">
          거래할 종목을 찾을 수 없습니다. <Link to="/market">시장으로 돌아가기</Link>
        </p>
      </div>
    );
  }

  const ratio = sessionRate(quote);
  const watched = snapshot.watch.includes(code);

  return (
    <div className="page-stack integration-tools trade-page">
      {snapshot.portfolioError && <p role="status" className="empty">{snapshot.portfolioError}</p>}

      <header className="trade-head" aria-labelledby="trade-name">
        <div className="trade-head-row">
          <Link className="trade-back" to={MARKET_PATH(code)}>← 종목 상세</Link>
          <GameIcon code={code} name={listing.name} size="large" />
          <div className="trade-head-copy">
            <h1 id="trade-name" className="trade-head-title">
              {listing.name}
            </h1>
            <p className="trade-head-sub">
              {listing.publisher} · {listing.genre} · {code}
            </p>
          </div>
          <div className="trade-head-actions">
            <div className="trade-head-price">
              <PriceCell className="num trade-head-price-value" value={quote.price} />
              <Delta change={quote.price - quote.prevClose} ratio={ratio} />
            </div>
            <button
              type="button"
              className={`watch-button is-text${watched ? " is-on" : ""}`}
              aria-pressed={watched}
              aria-label={watched ? "관심 종목 해제" : "관심 종목 등록"}
              onClick={() => api.toggleWatch(code)}
            >
              <span className="terminal-watch-mark" aria-hidden="true"><span className="watch-outline">☆</span><span className="watch-filled">★</span></span> 관심
            </button>
          </div>
        </div>
        <dl className="trade-stats" aria-label="오늘 시세">
          <div><dt>전일 종가</dt><dd className="num">{won(quote.prevClose)}</dd></div>
          <div><dt>거래량</dt><dd className="num">{shares(quote.volume)}</dd></div>
          <div><dt>시가총액</dt><dd className="num">{quote.marketCap ? compactWon(quote.marketCap) : "–"}</dd></div>
        </dl>
      </header>

      <div className="trade-layout">
        <StockChartPanel code={code} />

        <aside className="trade-order-column" aria-label={`${listing.name} 매수·매도 주문`}>
          <section className="trade-order-panel terminal-panel">
            <AdvancedOrderTicket
              onShowOrders={() => { setAccountTab("orders"); document.querySelector(".trade-account-panel")?.scrollIntoView({ block: "nearest" }); }}
              key={code}
              code={code}
              selectedLimitPrice={selectedQuote?.code === code ? selectedQuote.price : null}
              selectedLimitPriceRevision={selectedQuote?.code === code ? selectedQuote.revision : undefined}
            />
          </section>
        </aside>

        <section className="trade-book-panel terminal-panel" aria-labelledby="trade-book-title">
          <header className="trade-order-head">
            <h2 id="trade-book-title">호가창</h2>
          </header>
          <OrderBook code={code} onPriceSelect={(price) => setSelectedQuote((previous) => ({ code, price, revision: (previous?.revision ?? 0) + 1 }))} />
        </section>

        <TradeAccountPanel code={code} tab={accountTab} onTabChange={setAccountTab} />
      </div>
    </div>
  );
}
