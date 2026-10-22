import { GameIcon } from "../../components/GameIcon";
import { LoginCard } from "../../components/LoginCard";
import { indexValue, rate } from "../../market/format";
import { useMarket } from "../../market/MarketProvider";
import { sessionRate } from "../../market/selectors";
import { LISTING_BY_CODE } from "../../market/universe";
import type { LoginMethod } from "../../router";

/**
 * /login 으로 직접 들어왔을 때. 왼쪽은 지금 시장이 실제로 움직이는 모습, 오른쪽은 로그인.
 * 로그인 카드(구글·이메일·앱 딥링크 흐름)는 모달과 같은 부품을 그대로 쓴다.
 */
export function NxLoginPage({ method }: { method: LoginMethod }) {
  const snapshot = useMarket();
  const { value, prevClose } = snapshot.index;
  const change = prevClose ? (value - prevClose) / prevClose : 0;
  const movers = [...snapshot.codes]
    .filter((code) => snapshot.quotes[code])
    .sort((a, b) => Math.abs(sessionRate(snapshot.quotes[b])) - Math.abs(sessionRate(snapshot.quotes[a])))
    .slice(0, 4);

  return (
    <div className="nx-login">
      <section className="nx-login-market" aria-label="지금 시장">
        <p className="nx-panel-title">게임주 지수 · 지금</p>
        <p className="nx-num nx-login-index">{indexValue(value)}</p>
        <p className={`nx-num nx-login-change ${change > 0 ? "is-up" : change < 0 ? "is-down" : ""}`}>{rate(change)}</p>
        <ul className="nx-login-movers">
          {movers.map((code) => {
            const ratio = sessionRate(snapshot.quotes[code]);
            return (
              <li key={code}>
                <GameIcon code={code} size="small" />
                <span>{LISTING_BY_CODE[code]?.name ?? code}</span>
                <span className={`nx-num ${ratio > 0 ? "is-up" : ratio < 0 ? "is-down" : ""}`}>{rate(ratio)}</span>
              </li>
            );
          })}
        </ul>
        <p className="nx-login-copy">게임 뉴스와 투자자들의 주문으로 가격이 움직이는 모의 주식 시장이에요. 가입하면 모의 자금 100만 원으로 시작하고, 실제 돈은 오가지 않아요.</p>
      </section>
      <section className="nx-login-card" aria-labelledby="login-title">
        <LoginCard method={method} />
      </section>
    </div>
  );
}
