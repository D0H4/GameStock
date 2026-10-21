import { useSyncExternalStore } from "react";

/*
 * 새 화면이 기본이다. 기존 화면은 ?ui=classic(또는 아래 안내줄의 버튼)으로만 연다.
 * 링크 이동 때 쿼리가 사라지므로 기존 화면을 고른 사실은 브라우저에 기억해 두고,
 * ?ui=next로 다시 새 화면으로 돌아온다.
 * 저장소를 못 쓰는 환경(사생활 보호 창 등)에서는 이번 방문 동안만 유지된다.
 */
const UI_KEY = "shuen.ui";
const THEME_KEY = "shuen.theme";

export type NxTheme = "dark" | "light";

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    /* 기억하지 못해도 화면은 그대로 동작한다. */
  }
}

let nextUi = false;
let theme: NxTheme = "dark";
const listeners = new Set<() => void>();

if (typeof window !== "undefined") {
  const param = new URLSearchParams(window.location.search).get("ui");
  if (param === "next") write(UI_KEY, null);
  if (param === "classic") write(UI_KEY, "classic");
  /* 앱 안의 WebView(app-shell=1)는 라이트 전용 네이티브 화면과 이어지므로 명시적으로 고를 때만 새 화면을 쓴다. */
  const appShell = new URLSearchParams(window.location.search).get("app-shell") === "1";
  nextUi = param === "next" || (param !== "classic" && !appShell && read(UI_KEY) !== "classic");
  theme = read(THEME_KEY) === "light" ? "light" : "dark";
}

function emit(): void {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function isNextUi(): boolean {
  return nextUi;
}

/** 기존 화면으로 돌아간다. 새로고침 없이 바로 바뀌도록 구독자에게 알린다. */
export function leaveNextUi(): void {
  nextUi = false;
  write(UI_KEY, "classic");
  emit();
}

export function useNextUi(): boolean {
  return useSyncExternalStore(subscribe, () => nextUi, () => false);
}

export function useNxTheme(): NxTheme {
  return useSyncExternalStore(subscribe, () => theme, () => "dark");
}

export function toggleNxTheme(): void {
  theme = theme === "dark" ? "light" : "dark";
  write(THEME_KEY, theme);
  emit();
}
