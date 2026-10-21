import { useSyncExternalStore } from "react";

/*
 * 화면 밝기 선택. 웹은 다크가 기본이고, 앱 안 WebView(app-shell=1)는 라이트 전용인
 * 네이티브 화면과 이어지므로 라이트가 기본이다. 고른 값은 브라우저에 기억해 둔다.
 * 저장소를 못 쓰는 환경(사생활 보호 창 등)에서는 이번 방문 동안만 유지된다.
 */
const THEME_KEY = "shuen.theme";

export type NxTheme = "dark" | "light";

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    /* 기억하지 못해도 화면은 그대로 동작한다. */
  }
}

let theme: NxTheme = "dark";
const listeners = new Set<() => void>();

if (typeof window !== "undefined") {
  const stored = read(THEME_KEY);
  const appShell = new URLSearchParams(window.location.search).get("app-shell") === "1";
  theme = stored === "light" || stored === "dark" ? stored : appShell ? "light" : "dark";
  /* 예전 화면 전환 기록은 더 쓰지 않는다. */
  try { window.localStorage.removeItem("shuen.ui"); } catch { /* 무시 */ }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useNxTheme(): NxTheme {
  return useSyncExternalStore(subscribe, () => theme, () => "dark");
}

export function toggleNxTheme(): void {
  theme = theme === "dark" ? "light" : "dark";
  write(THEME_KEY, theme);
  for (const listener of listeners) listener();
}
