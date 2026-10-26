import { useEffect } from 'react';
import { AppState } from 'react-native';
import { request } from './auth';

// 화면 밖에서 생긴 보상(관심 종목 미션 자동 지급, 새 칭호)을 앱 알림으로 넘기는 통로.
const listeners = new Set();

/** icon은 Ionicons 이름. 알림은 받은 순서대로 하나씩 뜬다. */
export function announce(text, icon) {
  for (const listener of listeners) listener({ text, icon });
}

export function onAnnounce(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// 총 자산·보유 수량처럼 잠깐만 채워지는 조건도 놓치지 않도록 웹 TitleWatcher와 같은 주기로 확인한다.
const CHECK_INTERVAL_MS = 20_000;

/** 주간 칭호는 "주간 수익률 1위 2관왕"처럼 횟수를 붙인다(웹 titleLabel과 같다). */
const titleLabel = title => (title.weekly && title.owned ? `${title.name} ${title.count}관왕` : title.name);

/**
 * 로그인한 동안 칭호 조건을 확인해 새 칭호를 알린다. 서버는 새 칭호를 한 번만 돌려준다.
 * 앱이 백그라운드에 있으면 확인하지 않고, 앱으로 돌아오면 바로 한 번 확인한다.
 */
export function useTitleWatcher(enabled) {
  useEffect(() => {
    if (!enabled) return undefined;
    let checking = false;
    let stopped = false;
    const run = async () => {
      if (checking || stopped || AppState.currentState !== 'active') return;
      checking = true;
      try {
        const status = await request('/api/titles/check', { method: 'POST' });
        if (stopped) return;
        for (const title of status?.newlyAwarded ?? []) announce(`새 칭호를 받았어요: ${titleLabel(title)}`, 'ribbon');
      } catch { /* 다음 주기에 다시 확인한다. */ } finally {
        checking = false;
      }
    };
    void run();
    const poll = setInterval(() => void run(), CHECK_INTERVAL_MS);
    const subscription = AppState.addEventListener('change', next => { if (next === 'active') void run(); });
    return () => {
      stopped = true;
      clearInterval(poll);
      subscription.remove();
    };
  }, [enabled]);
}
