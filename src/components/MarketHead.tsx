import { Delta } from "./Delta";
import { PriceCell } from "./PriceCell";
import { Sparkline } from "./Sparkline";
import { openLoginPrompt, useAuthUser } from "../lib/auth";
import { useProfile } from "../lib/profile";
import { indexValue, won } from "../market/format";
import { useMarket } from "../market/MarketProvider";
import { totals } from "../market/selectors";

/** 홈 맨 위. 지수 하나와 내 자산 하나, 그게 전부다. */
export function MarketHead() {
  const snapshot = useMarket();
  const index = snapshot.index;
  const change = index.value - index.prevClose;
  const ratio = change / index.prevClose;
  const tone = ratio > 0 ? "up" : ratio < 0 ? "down" : "flat";
  const money = totals(snapshot);
  const auth = useAuthUser();
  const profile = useProfile();

  return (
    <section className="head" aria-label="시장 요약">
      <div>
        <p className="head-label">게임주 지수</p>
        <p className="head-value">
          <PriceCell value={index.value} format={indexValue} />
        </p>
        <p className="head-sub">
          <Delta change={change} ratio={ratio} showAmount={false} />
          <Sparkline series={index.series} prevClose={index.prevClose} tone={tone} width={120} height={28} />
        </p>
      </div>
      {/* 로그인 전에는 계정이 없으니 자산 숫자를 보여 주지 않는다. 확인이 끝나기 전에는 자리를 비워 둔다.
          관리자 계정은 투자하지 않으니 자산 칸을 두지 않는다. */}
      {profile?.admin ? null : auth.user ? (
        <div className="head-me">
          <p className="head-label">{money.loan > 0 ? "내 순자산" : "내 자산"}</p>
          <p className="head-value is-small">
            <PriceCell value={money.net} format={won} />
          </p>
          <p className="head-sub">
            <Delta change={money.pnl} ratio={money.pnlRate} />
          </p>
        </div>
      ) : auth.ready ? (
        <div className="head-me is-guest">
          <p className="head-label">내 자산</p>
          <p className="head-guest">로그인하면 모의투자 자산과 손익이 여기에 보여요.</p>
          <button type="button" className="account-button is-primary" onClick={() => openLoginPrompt("/")}>
            로그인하고 시작하기
          </button>
        </div>
      ) : null}
    </section>
  );
}
