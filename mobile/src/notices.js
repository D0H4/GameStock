import { useEffect, useState } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { api } from './market';

/*
 * 웹 src/notice.ts와 같은 공지 규칙. 공지는 관리자 화면(공지 탭)에서 쓰고 /api/content로 받는다.
 * 한 번 들어올 때 아직 확인하지 않은 가장 최근 공지 하나만 띄운다. 공지를 고치면 열쇠가 바뀌어 다시 보인다.
 */
const SEEN_KEY = 'gamestock-site-notice-seen';
const CIRCUIT_HIDE_KEY = 'gamestock-circuit-level3-hide-until';

// 콘텐츠 API에 닿지 못할 때만 보여 주는 공지. API가 빈 목록을 주면 공지가 없다는 뜻이라 이것도 띄우지 않는다.
const FALLBACK_NOTICES = [
  {
    key: '2026-10-10',
    heading: '프로모션 코드',
    body: '충전 페이지의 프로모션 코드 칸에 입력하면 특별 칭호 3종을 받을 수 있어요.',
    code: 'syuen',
  },
];

// 콘텐츠 API에는 코드 칸이 없어서 본문 마지막 줄 "코드: ..."로 저장한다.
const CODE_LINE = /\n*코드:[ \t]*(\S+)[ \t]*$/;

function noticeItem(row) {
  const match = String(row.body ?? '').match(CODE_LINE);
  return {
    key: `${row.id}:${row.updated_at ?? ''}`,
    heading: row.title,
    body: match ? row.body.slice(0, match.index).trimEnd() : row.body,
    ...(match ? { code: match[1] } : {}),
    ...(row.image_url ? { image: row.image_url } : {}),
    ...(row.action_label && row.action_url ? { action: { label: row.action_label, url: row.action_url } } : {}),
    ...(row.dismiss_label ? { dismissLabel: row.dismiss_label } : {}),
  };
}

const readSeen = async () => {
  try { return ((await AsyncStorage.getItem(SEEN_KEY)) ?? '').split(',').filter(Boolean); } catch { return []; }
};

/** 이번에 띄울 공지 하나와 확인 처리. 공지를 다 읽기 전과 띄울 공지가 없을 때 notice는 null. */
export function useSiteNotice() {
  const [state, setState] = useState({ notice: null, seen: [] });
  useEffect(() => {
    let alive = true;
    Promise.all([
      api('/api/content?kind=NOTICE&limit=20').then(rows => rows.map(noticeItem)).catch(() => FALLBACK_NOTICES),
      readSeen(),
    ]).then(([notices, seen]) => {
      if (alive) setState({ notice: notices.find(item => !seen.includes(item.key)) ?? null, seen });
    });
    return () => { alive = false; };
  }, []);
  const dismiss = () => {
    const { notice, seen } = state;
    if (!notice) return;
    // 웹과 같은 방식으로 최근 50개만 남긴다. 남은 공지는 다음에 앱을 켤 때 차례로 뜬다.
    const next = [...seen, notice.key].slice(-50);
    AsyncStorage.setItem(SEEN_KEY, next.join(',')).catch(() => {});
    setState({ notice: null, seen: next });
  };
  return { notice: state.notice, dismiss };
}

const KST_OFFSET = 9 * 60 * 60_000;
const DAY = 24 * 60 * 60_000;
// 확인한 서킷브레이커 단계. 탭을 옮겨도 다시 뜨지 않고, 다음 단계나 다음 발동은 새로 알린다.
const acknowledged = new Set();

/**
 * 시장 전체 서킷브레이커 알림. 단계·국면마다 한 번 띄운다.
 * 3단계는 그날 거래가 끝난 것이라 "오늘 하루 보지 않기"를 누르면 한국시간 자정까지 숨긴다.
 */
export function useCircuitNotice(restriction) {
  const [hiddenUntil, setHiddenUntil] = useState(null);
  const [, setVersion] = useState(0);
  useEffect(() => {
    AsyncStorage.getItem(CIRCUIT_HIDE_KEY).then(value => setHiddenUntil(Number(value) || 0)).catch(() => setHiddenUntil(0));
  }, []);
  useEffect(() => {
    if (!hiddenUntil || hiddenUntil <= Date.now()) return undefined;
    const timer = setTimeout(() => setHiddenUntil(0), Math.min(hiddenUntil - Date.now() + 1, DAY));
    return () => clearTimeout(timer);
  }, [hiddenUntil]);

  // 숨김 기록을 다 읽기 전에는 띄우지 않는다. 숨긴 사람에게 창이 잠깐 비치지 않게 한다.
  if (hiddenUntil === null || restriction?.kind !== 'CIRCUIT_BREAKER') return { restriction: null };
  if (restriction.level === 3 && hiddenUntil > Date.now()) return { restriction: null };
  const key = `${restriction.startedAt}:${restriction.level}:${restriction.phase}`;
  if (acknowledged.has(key)) return { restriction: null };
  const close = () => { acknowledged.add(key); setVersion(value => value + 1); };
  const confirm = () => {
    if (restriction.level !== 3) { close(); return; }
    const until = (Math.floor((Date.now() + KST_OFFSET) / DAY) + 1) * DAY - KST_OFFSET;
    AsyncStorage.setItem(CIRCUIT_HIDE_KEY, String(until)).catch(() => {});
    setHiddenUntil(until);
  };
  return { restriction, close, confirm };
}

// setTimeout은 약 24.8일을 넘기면 바로 실행되므로 그보다 긴 정지는 하루마다 다시 확인한다.
const MAX_TIMER_MS = DAY;

/** 남은 이용 정지의 끝 시각(ms). 정지가 없거나 끝났으면 null. 끝나는 순간 다시 계산한다. */
export function useSuspendedUntil(profile) {
  const until = profile?.tradeBannedUntil ? Date.parse(profile.tradeBannedUntil) : NaN;
  const [now, setNow] = useState(() => Date.now());
  const active = Number.isFinite(until) && until > now;
  useEffect(() => {
    if (!active) return undefined;
    const timer = setTimeout(() => setNow(Date.now()), Math.min(until - Date.now() + 1000, MAX_TIMER_MS));
    return () => clearTimeout(timer);
  }, [active, until, now]);
  return active ? until : null;
}

/** 한국시간 "2026년 10월 27일 14:30". 기기 시간대와 상관없이 같은 값을 보여 준다. */
export function kstLong(ms) {
  if (!Number.isFinite(ms)) return '-';
  const at = new Date(ms + KST_OFFSET);
  const pad = value => String(value).padStart(2, '0');
  return `${at.getUTCFullYear()}년 ${at.getUTCMonth() + 1}월 ${at.getUTCDate()}일 ${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}`;
}
