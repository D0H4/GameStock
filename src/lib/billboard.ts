import { useSyncExternalStore } from "react";
import { apiFetch } from "./api";
import { dayLabel, minutes, serverTimestamp } from "../market/format";

/*
 * 홈 상단 전광판. 투자자가 모의 자금을 내고 올린 한 줄 문구를 정해진 시간 동안 보인다.
 * 홈 띠와 전광판 화면이 같은 값을 쓰도록 한 곳에서 1분마다 받아 나눠 준다.
 */
export type BillboardShowing = { id: number; nickname: string; body: string; startsAt: string; endsAt: string };
export type BillboardBoard = {
  current: BillboardShowing | null;
  upcoming: BillboardShowing[];
  pricePerHour: number;
  durations: number[];
  minLength: number;
  maxLength: number;
  /** 시간별로 지금 사면 시작하는 시각. 이틀 안에 자리가 없으면 null. */
  nextStarts: Record<string, string | null>;
};
export type BillboardState = "UPCOMING" | "LIVE" | "ENDED" | "HIDDEN" | "CANCELLED";
export type BillboardBooking = {
  id: number;
  body: string;
  hours: number;
  price: number;
  startsAt: string;
  endsAt: string;
  state: BillboardState;
  hiddenReason: string | null;
  refunded: number;
  createdAt: string;
};
export type BillboardResult = { message: string; booking: BillboardBooking | null };

const POLL_MS = 60_000;
let board: BillboardBoard | null = null;
let failed = false;
let timer = 0;
let endTimer = 0;
const listeners = new Set<() => void>();

function emit() {
  for (const listener of listeners) listener();
}

/** 보이던 문구가 끝나는 순간에 맞춰 한 번 더 받는다. 1분 주기를 기다리면 끝난 문구가 남아 있다. */
function scheduleEnd() {
  window.clearTimeout(endTimer);
  const end = board?.current ? serverTimestamp(board.current.endsAt) : Number.NaN;
  if (Number.isFinite(end)) endTimer = window.setTimeout(() => void refreshBillboard(), Math.max(0, end - Date.now()) + 500);
}

export async function refreshBillboard(): Promise<void> {
  try {
    board = await apiFetch<BillboardBoard>("/api/billboard", { auth: false });
    failed = false;
  } catch {
    failed = true;
  }
  scheduleEnd();
  emit();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  if (listeners.size === 1) {
    void refreshBillboard();
    timer = window.setInterval(() => void refreshBillboard(), POLL_MS);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      window.clearInterval(timer);
      window.clearTimeout(endTimer);
    }
  };
}

export function useBillboard(): { board: BillboardBoard | null; failed: boolean } {
  const value = useSyncExternalStore(subscribe, () => board);
  const error = useSyncExternalStore(subscribe, () => failed);
  return { board: value, failed: error };
}

/** "지금 바로"·시작 시각 표시를 분 단위로 맞추려고 30초마다 다시 읽는 시계. */
export function useMinuteClock(): number {
  return useSyncExternalStore(subscribeMinute, () => minuteNow);
}
let minuteNow = Date.now();
let minuteTimer = 0;
const minuteListeners = new Set<() => void>();
function subscribeMinute(listener: () => void) {
  minuteListeners.add(listener);
  if (minuteListeners.size === 1) {
    minuteNow = Date.now();
    minuteTimer = window.setInterval(() => {
      minuteNow = Date.now();
      for (const item of minuteListeners) item();
    }, 30_000);
  }
  return () => {
    minuteListeners.delete(listener);
    if (minuteListeners.size === 0) window.clearInterval(minuteTimer);
  };
}

/** 지금 바로 / 오늘 14:00 / 내일 02:00 / 10월 29일 09:00 */
export function slotTime(at: string, now: number): string {
  const time = serverTimestamp(at);
  if (time - now < 60_000) return "지금 바로";
  const day = dayLabel(time, now);
  const tomorrow = dayLabel(now, time) === "어제";
  return `${day === "오늘" ? "오늘" : tomorrow ? "내일" : day} ${minutes(time)}`;
}

export function buyBillboard(body: string, hours: number) {
  return apiFetch<BillboardResult>("/api/billboard", { method: "POST", body: JSON.stringify({ body, hours }) });
}

export function cancelBillboard(id: number) {
  return apiFetch<BillboardResult>(`/api/billboard/${id}`, { method: "DELETE" });
}

export function reportBillboard(id: number, detail: string) {
  return apiFetch<{ id: number }>(`/api/billboard/${id}/report`, { method: "POST", body: JSON.stringify({ detail }) });
}

export function myBillboard(signal?: AbortSignal) {
  return apiFetch<BillboardBooking[]>("/api/billboard/mine", { signal });
}
