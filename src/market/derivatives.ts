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

export interface DerivativeOrderResult {
  message: string;
  account: DerivativeAccount;
}

const POLL_MS = 2000;

/**
 * ETF·파생 시세는 2초마다 새로 받는다. 주식 스냅샷과 별도 엔드포인트라
 * 기존 시장 갱신 주기와 독립적으로 움직인다.
 */
export function useDerivativeMarket() {
  const auth = useAuthUser();
  const [snapshot, setSnapshot] = useState<DerivativeSnapshot | null>(null);
  const [account, setAccount] = useState<DerivativeAccount | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (user: User | null, active: () => boolean) => {
    try {
      const [nextSnapshot, nextAccount] = await Promise.all([
        apiFetch<DerivativeSnapshot>("/api/derivatives", { auth: false }),
        user ? apiFetch<DerivativeAccount>("/api/derivatives/account") : Promise.resolve(null),
      ]);
      if (!active()) return;
      setSnapshot(nextSnapshot);
      setAccount(nextAccount);
      setError(null);
    } catch (failure) {
      if (!active()) return;
      setError(failure instanceof Error ? failure.message : "파생 시세를 불러오지 못했습니다.");
    } finally {
      if (active()) setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    const active = () => !cancelled;
    setAccount(null);
    setLoading(true);
    void load(auth.user, active);
    const timer = window.setInterval(() => void load(auth.user, active), POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [auth.user, load]);

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
