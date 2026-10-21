import { useSyncExternalStore } from "react";

/*
 * 새 화면(?ui=next)은 하나씩 옮기는 중이라 켜고 끌 수 있어야 한다.
 * 링크 이동 때 쿼리가 사라지므로 선택은 브라우저에 기억해 둔다.
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
  if (param === "next") write(UI_KEY, "next");
  if (param === "classic") write(UI_KEY, null);
  nextUi = param === "next" || (param !== "classic" && read(UI_KEY) === "next");
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
  write(UI_KEY, null);
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
