import type { CSSProperties } from "react";
import { Link } from "../components/Link";
import { rate, won } from "../market/format";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { useSteadySnapshot } from "../market/useSteadySnapshot";
import { MARKET_PATH } from "../router";

/** 종목 하나가 띠를 한 칸 지나가는 시간(초). 종목 수에 곱해 전체 한 바퀴 시간을 정한다. 상단 바 시절(4초)보다 40% 넘게 느리다. */
const TAPE_SECONDS_PER_ITEM = 7;

/** 홈의 지수와 종목 순위 사이를 흐르는 전 종목 시세 띠. 값은 5초마다 한꺼번에 바뀐다. */
export function PriceTape() {
  const snapshot = useSteadySnapshot();
  const codes = snapshot.codes.filter((code) => snapshot.quotes[code] && LISTING_BY_CODE[code]);
  if (codes.length === 0) return null;

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
    <div className="nx-tape" style={{ "--nx-tape-duration": `${codes.length * TAPE_SECONDS_PER_ITEM}s` } as CSSProperties}>
      <div className="nx-tape-track">
        <ul aria-label="종목별 현재 시세">{items(false)}</ul>
        <ul aria-hidden="true">{items(true)}</ul>
      </div>
    </div>
  );
}
