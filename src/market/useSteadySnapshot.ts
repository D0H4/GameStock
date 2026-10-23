import { useEffect, useRef, useState } from "react";
import { useMarket } from "./MarketProvider";
import type { MarketSnapshot } from "./types";

export const STEADY_REFRESH_MS = 5_000;

/**
 * 체결마다 값이 따로 바뀌면 지도와 띠가 쉴 새 없이 깜빡여, 5초마다 그때의 시세로 한꺼번에 바꾼다.
 * 큰 지수 숫자처럼 매 틱 따라가야 하는 자리는 useMarket을 그대로 쓴다.
 */
export function useSteadySnapshot(): MarketSnapshot {
  const live = useMarket();
  const latest = useRef(live);
  const [shown, setShown] = useState(live);

  useEffect(() => {
    latest.current = live;
  }, [live]);

  useEffect(() => {
    const timer = window.setInterval(() => setShown(latest.current), STEADY_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, []);

  // 첫 시세가 오기 전에 잡힌 빈 화면으로 5초를 기다리지 않는다.
  return shown.codes.length > 0 ? shown : live;
}
