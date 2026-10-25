import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "../lib/api";
import { useAuthUser } from "../lib/auth";
import type { User } from "firebase/auth";

export interface DerivativeConstituent {
  code: string;
  name: string;
  weight: number;
  changePercent: number;
}

export interface EtfQuote {
  code: string;
  name: string;
  theme: string;
  description: string;
  price: number;
  prevClose: number;
  changePercent: number;
  constituents: DerivativeConstituent[];
  series: number[];
}

export interface FutureQuote {
  code: string;
  name: string;
  price: number;
  prevClose: number;
  changePercent: number;
  multiplier: number;
  leverages: number[];
  maintenanceRate: number;
  series: number[];
}

export interface OptionCycle {
  id: string;
  label: string;
  tag: string;
  expiryAt: string;
  expiryEpoch: number;
  secondsLeft: number;
}

export interface OptionContract {
  id: string;
  cycleId: string;
  type: "CALL" | "PUT";
  strike: number;
  /** 지수 포인트 단위의 모의 프리미엄. */
  mark: number;
  /** 1계약 프리미엄(원). */
  premium: number;
  breakeven: number;
  expiryAt: string;
  expiryEpoch: number;
  secondsLeft: number;
}

export interface DerivativeSnapshot {
  indexValue: number;
  indexPrevClose: number;
  indexChangePercent: number;
  etfs: EtfQuote[];
  future: FutureQuote;
  cycles: OptionCycle[];
  options: OptionContract[];
  serverTime: string;
}

export type DerivativeKind = "ETF" | "FUTURE" | "OPTION";

export interface DerivativePosition {
  id: number;
  kind: DerivativeKind;
  symbol: string;
  name: string;
  side: string;
  quantity: number;
  entryPrice: number;
  markPrice: number;
  entryValue: number;
  margin: number;
  leverage: number;
  optionType: "CALL" | "PUT" | null;
  strike: number;
  expiryAt: string | null;
  value: number;
  unrealizedPnl: number;
  unrealizedPercent: number;
  status: string;
  openedAt: string;
}

export interface DerivativeTrade {
  id: number;
  positionId: number;
  action: string;
  kind: DerivativeKind;
  symbol: string;
  side: string;
  quantity: number;
  price: number;
  cashFlow: number;
  fee: number;
  createdAt: string;
}

export interface DerivativeAccount {
  cash: number;
  positionValue: number;
  equity: number;
  unrealizedPnl: number;
  realizedPnl: number;
  totalFees: number;
  positions: DerivativePosition[];
  trades: DerivativeTrade[];
}

export interface DerivativeOrderRequest {
  kind: DerivativeKind;
  side: "BUY" | "SELL" | "LONG" | "SHORT" | "CALL" | "PUT";
  quantity: number;
  symbol?: string;
  leverage?: number;
  cycleId?: "10M" | "1H" | "SUN";
  strike?: number;
  expiryEpoch?: number;
}

export interface OptionFlowBucket {
  /** 봉 시작(epoch 초). */
  at: number;
  /** 봉 안 마지막 지수 관측. 기록이 없으면 null. */
  index: number | null;
  callVolume: number;
  putVolume: number;
  callPremium: number;
  putPremium: number;
  /** (콜 매수 − 콜 청산) − (풋 매수 − 풋 청산). 양수면 상승 쪽 돈이 많다. */
  netPremium: number;
}

export interface OptionFlow {
  range: OptionFlowRange;
  intervalSeconds: number;
  from: number;
  to: number;
  callVolume: number;
  putVolume: number;
  callPremium: number;
  putPremium: number;
  netPremium: number;
  buckets: OptionFlowBucket[];
}

export type OptionFlowRange = "1h" | "6h" | "1d" | "1w";

/** 기간마다 봉 간격을 하나로 정한다. 서버 상한(봉 400개) 안쪽이다. */
export const OPTION_FLOW_INTERVAL: Record<OptionFlowRange, "1m" | "5m" | "1h"> = { "1h": "1m", "6h": "5m", "1d": "5m", "1w": "1h" };

export interface DerivativeOrderResult {
  message: string;
  account: DerivativeAccount;
}

const POLL_MS = 2000;

/**
 * ETF·파생 시세는 2초마다 새로 받는다. 주식 스냅샷과 별도 엔드포인트라
 * 기존 시장 갱신 주기와 독립적으로 움직인다.
 */
// Public quotes survive route remounts. The account survives them only for the same signed-in user,
// so switching product tabs does not flash the sign-in notice before the account reloads.
let lastSnapshot: DerivativeSnapshot | null = null;
let lastAccount: { uid: string; account: DerivativeAccount } | null = null;
const cachedAccount = (user: User | null) => user && lastAccount?.uid === user.uid ? lastAccount.account : null;

export function useDerivativeMarket(includeAccount = true) {
  const auth = useAuthUser();
  const [snapshot, setSnapshot] = useState<DerivativeSnapshot | null>(() => lastSnapshot);
  const [account, setAccount] = useState<DerivativeAccount | null>(() => includeAccount ? cachedAccount(auth.user) : null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (user: User | null, active: () => boolean) => {
    try {
      const [nextSnapshot, nextAccount] = await Promise.all([
        apiFetch<DerivativeSnapshot>("/api/derivatives", { auth: false }),
        user && includeAccount ? apiFetch<DerivativeAccount>("/api/derivatives/account") : Promise.resolve(null),
      ]);
      if (!active()) return;
      lastSnapshot = nextSnapshot;
      setSnapshot(nextSnapshot);
      setAccount(nextAccount);
      setError(null);
    } catch (failure) {
      if (!active()) return;
      setError(failure instanceof Error ? failure.message : "파생 시세를 불러오지 못했습니다.");
    } finally {
      if (active()) setLoading(false);
    }
  }, [includeAccount]);

  useEffect(() => {
    let cancelled = false;
    const active = () => !cancelled;
    setAccount(includeAccount ? cachedAccount(auth.user) : null);
    setLoading(true);
    void load(auth.user, active);
    const timer = window.setInterval(() => void load(auth.user, active), POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [auth.user, includeAccount, load]);

  useEffect(() => {
    if (!auth.user) lastAccount = null;
    else if (account) lastAccount = { uid: auth.user.uid, account };
  }, [auth.user, account]);

  const refresh = useCallback(async () => {
    if (!auth.user) return;
    const next = await apiFetch<DerivativeAccount>("/api/derivatives/account");
    setAccount(next);
  }, [auth.user]);

  return { snapshot, account, loading, error, refresh, setAccount, user: auth.user, ready: auth.ready };
}

export function openDerivative(request: DerivativeOrderRequest): Promise<DerivativeOrderResult> {
  return apiFetch<DerivativeOrderResult>("/api/derivatives/positions", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function closeDerivative(positionId: number, quantity: number): Promise<DerivativeOrderResult> {
  return apiFetch<DerivativeOrderResult>(`/api/derivatives/positions/${positionId}/close`, {
    method: "POST",
    body: JSON.stringify({ quantity }),
  });
}

const FLOW_POLL_MS = 15_000;

/** 옵션 거래 흐름. 서버가 5초씩 묶어 주므로 15초마다 다시 받는다. 기간을 바꾸면 이전 값을 지우지 않고 새 값으로 바꿔 끼운다. */
export function useOptionFlow(range: OptionFlowRange) {
  const [flow, setFlow] = useState<OptionFlow | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const next = await apiFetch<OptionFlow>(`/api/derivatives/options/flow?range=${range}&interval=${OPTION_FLOW_INTERVAL[range]}`, { auth: false });
        if (!active) return;
        setFlow(next);
        setError(false);
      } catch {
        if (active) setError(true);
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), FLOW_POLL_MS);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [range]);

  // 새 기간을 받는 동안에는 이전 그래프를 흐리게 남겨 둔다.
  return { flow, pending: flow !== null && flow.range !== range, error };
}

/** 만기까지 남은 시간을 mm:ss 또는 n일 hh:mm로 표시한다. */
export function countdown(expiryEpoch: number, now: number): string {
  const seconds = Math.max(0, Math.floor((expiryEpoch * 1000 - now) / 1000));
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  const rest = seconds % 60;
  if (days > 0) return `${days}일 ${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
  if (hours > 0) return `${hours}:${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`;
  return `${minutes}:${String(rest).padStart(2, "0")}`;
}

/** 1초마다 갱신되는 현재 시각. 만기 카운트다운에만 쓴다. */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}
