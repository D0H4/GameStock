import { useEffect, useState } from "react";
import { apiFetch } from "../../lib/api";
import { rate, serverTimestamp, shares, since } from "../../market/format";

type Drivers = {
  stockCode: string; currentPrice: number; previousPrice: number; changePercent: number;
  newsImpact: number; newsCount: number;
  userBuyVolume: number; userSellVolume: number; botBuyVolume: number; botSellVolume: number;
  openBuyVolume: number; openSellVolume: number;
  latestNewsAt: string | null; latestTradeAt: string | null; reason: string;
};

/** 서버의 뉴스 지표 범위(MarketService.priceDrivers가 -10~10으로 자른다). */
const IMPACT_LIMIT = 10;

/** 매수와 매도가 한 줄을 나눠 갖는 막대. 가운데 눈금이 반반 지점이다. */
function Tug({ label, buy, sell }: { label: string; buy: number; sell: number }) {
  const total = buy + sell;
  const share = total > 0 ? buy / total : 0.5;
  return (
    <div className="nx-tug">
      <dt>{label}</dt>
      <dd>
        <span className="nx-tug-side is-buy nx-num">매수 {shares(buy)}</span>
        <span className="nx-tug-bar" role="img"
          aria-label={total > 0 ? `매수 ${Math.round(share * 100)}%, 매도 ${100 - Math.round(share * 100)}%` : "거래 없음"}>
          {total > 0 ? <>
            <i className="is-buy" style={{ flexGrow: buy }} />
            <i className="is-sell" style={{ flexGrow: sell }} />
          </> : <i className="is-empty" />}
        </span>
        <span className="nx-tug-side is-sell nx-num">{shares(sell)} 매도</span>
      </dd>
    </div>
  );
}

/**
 * 지금 가격을 만든 재료: 24시간 뉴스 지표, 체결량, 걸려 있는 주문.
 * 원인을 단정하지 않고, 서버가 같은 표에서 읽은 값만 나란히 보인다.
 */
export function PriceDrivers({ code }: { code: string }) {
  const [drivers, setDrivers] = useState<Drivers | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    setDrivers(null);
    setFailed(false);
    const load = async () => {
      try {
        const next = await apiFetch<Drivers>(`/api/stocks/${code}/price-drivers`, { auth: false });
        if (active) { setDrivers(next); setFailed(false); }
      } catch {
        if (active) setFailed(true);
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 15_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [code]);

  const now = Date.now();
  const ago = (value: string) => since(serverTimestamp(value), now);
  // "방금"은 부사라 조사를 붙이면 "방금에"처럼 어색해진다.
  const lastTrade = (when: string) => (when === "방금" ? when : `${when}에`);

  return (
    <section className="nx-drivers" aria-labelledby="nx-drivers-title" aria-busy={!drivers && !failed}>
      <header>
        <h2 id="nx-drivers-title">움직임 근거</h2>
        <span className="nx-muted">최근 24시간</span>
      </header>
      {!drivers ? (
        <p className="nx-muted" role="status">{failed ? "근거를 불러오지 못했어요. 15초 뒤 다시 불러와요." : "근거를 모으고 있어요."}</p>
      ) : (() => {
        const impact = Math.max(-IMPACT_LIMIT, Math.min(IMPACT_LIMIT, drivers.newsImpact));
        const position = ((impact + IMPACT_LIMIT) / (IMPACT_LIMIT * 2)) * 100;
        const lean = impact > 0.2 ? "호재 쪽" : impact < -0.2 ? "악재 쪽" : "중립";
        return <>
          <p className="nx-drivers-reason">{drivers.reason}</p>
          <dl className="nx-drivers-grid">
            <div className="nx-impact">
              <dt>뉴스 지표</dt>
              <dd>
                <span className="nx-impact-track" role="img" aria-label={`뉴스 지표 ${impact.toFixed(1)}, ${lean}`}>
                  <i className="nx-impact-tick" style={{ left: `${position}%` }} />
                </span>
                <span className="nx-impact-legend"><span>악재</span><span className="nx-num">{lean} {impact > 0 ? "+" : ""}{impact.toFixed(1)}</span><span>호재</span></span>
                <span className="nx-muted">뉴스 <span className="nx-num">{drivers.newsCount}</span>건{drivers.latestNewsAt ? `, 마지막 뉴스 ${ago(drivers.latestNewsAt)}` : ""}</span>
              </dd>
            </div>
            <Tug label="체결" buy={drivers.userBuyVolume + drivers.botBuyVolume} sell={drivers.userSellVolume + drivers.botSellVolume} />
            <Tug label="대기 주문" buy={drivers.openBuyVolume} sell={drivers.openSellVolume} />
          </dl>
          <p className="nx-drivers-foot nx-muted">전일보다 <span className="nx-num">{rate(drivers.changePercent / 100)}</span> 움직였어요. {drivers.latestTradeAt ? `마지막 체결은 ${lastTrade(ago(drivers.latestTradeAt))} 있었어요.` : "아직 체결이 없어요."} 지표는 지난 흐름이고 다음 가격을 알려 주지 않아요.</p>
        </>;
      })()}
    </section>
  );
}
