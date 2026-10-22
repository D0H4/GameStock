import { useSyncExternalStore } from "react";

/*
 * 화면 밝기 선택. 웹과 앱 모두 다크가 기본이다. 고른 값은 브라우저에 기억해 둔다.
 * 저장소를 못 쓰는 환경(사생활 보호 창 등)에서는 이번 방문 동안만 유지된다.
 * 앱 안 WebView(app-shell=1)에서는 앱이 같은 키에 앱의 선택을 미리 적어 두고, 바뀌면
 * gamestock-native-theme 이벤트로 알린다. 웹에서 바꾼 값은 앱에 THEME 메시지로 돌려준다.
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
  theme = stored === "light" || stored === "dark" ? stored : "dark";
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

function apply(next: NxTheme): void {
  if (next === theme) return;
  theme = next;
  write(THEME_KEY, theme);
  for (const listener of listeners) listener();
}

export function toggleNxTheme(): void {
  apply(theme === "dark" ? "light" : "dark");
  const native = (window as Window & { ReactNativeWebView?: { postMessage(message: string): void } }).ReactNativeWebView;
  native?.postMessage(JSON.stringify({ type: "THEME", theme }));
}

if (typeof window !== "undefined") {
  window.addEventListener("gamestock-native-theme", (event) => {
    const next = (event as CustomEvent<unknown>).detail;
    if (next === "dark" || next === "light") apply(next);
  });
}
