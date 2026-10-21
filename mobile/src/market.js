import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { API_BASE_URL } from './config';

const STREAM_URL = API_BASE_URL.replace(/^http/, 'ws') + '/ws/stream';

export async function api(path, token) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!response.ok) {
    const error = new Error(`${path} ${response.status}`);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

/**
 * 공개 시세. REST로 첫 화면을 채우고, /ws/stream 구독으로 종목 가격을 실시간 갱신한다.
 * 소켓이 끊겨 있으면 5초마다, 연결돼 있으면 30초마다 REST로 뉴스와 시장 상태를 다시 읽는다.
 */
export function useMarket() {
  const [state, setState] = useState({ stocks: [], events: [], status: null, loaded: false, error: null });
  const [live, setLive] = useState(false);
  const socketRef = useRef(null);
  const liveRef = useRef(false);
  const codesRef = useRef([]);

  const load = useCallback(async () => {
    try {
      const [stocks, events, status] = await Promise.all([
        api('/api/stocks'), api('/api/market-events'), api('/api/market-status'),
      ]);
      codesRef.current = stocks.map(stock => stock.code);
      socketRef.current?.subscribe(codesRef.current);
      setState({ stocks, events, status, loaded: true, error: null });
    } catch (error) {
      setState(current => ({ ...current, loaded: true, error }));
    }
  }, []);

  useEffect(() => {
    let stopped = false;
    let socket = null;
    let retry = 0;
    let reconnect;
    let lastMessage = Date.now();
    const setConnected = value => { liveRef.current = value; setLive(value); };
    const send = message => { if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message)); };

    const connect = () => {
      if (stopped) return;
      const target = new WebSocket(STREAM_URL);
      socket = target;
      target.onopen = () => {
        lastMessage = Date.now();
        send({ type: 'SUBSCRIBE', symbols: codesRef.current, detailSymbols: [] });
      };
      target.onmessage = event => {
        if (socket !== target) return;
        let message;
        try { message = JSON.parse(event.data); } catch { return; }
        lastMessage = Date.now();
        retry = 0;
        if (message.type === 'ERROR') { setConnected(false); return; }
        setConnected(true);
        if (message.type !== 'MARKET_SYMBOL') return;
        const stock = message.payload?.stock;
        if (!stock) return;
        setState(current => ({
          ...current,
          stocks: current.stocks.map(old => (old.code === message.payload.symbol ? { ...old, ...stock } : old)),
        }));
      };
      target.onclose = () => {
        if (socket !== target) return;
        setConnected(false);
        if (!stopped) reconnect = setTimeout(connect, Math.min(15_000, 500 * 2 ** Math.min(retry++, 5)));
      };
      target.onerror = () => target.close();
    };

    socketRef.current = { subscribe: symbols => send({ type: 'SUBSCRIBE', symbols, detailSymbols: [] }) };
    const heartbeat = setInterval(() => {
      if (socket?.readyState === WebSocket.OPEN && Date.now() - lastMessage > 45_000) socket.close();
      else send({ type: 'PING' });
    }, 15_000);
    let lastRest = Date.now();
    const poll = setInterval(() => {
      const due = liveRef.current ? 30_000 : 5_000;
      if (Date.now() - lastRest < due) return;
      lastRest = Date.now();
      void load();
    }, 2_500);

    // 백그라운드에서는 소켓을 닫고, 앱으로 돌아오면 다시 연결하며 한 번 새로 읽는다.
    const appState = AppState.addEventListener('change', next => {
      if (next === 'active') {
        if (!socket || socket.readyState > WebSocket.OPEN) { retry = 0; connect(); }
        lastRest = Date.now();
        void load();
      } else if (next === 'background') {
        clearTimeout(reconnect);
        const closing = socket;
        socket = null;
        closing?.close();
        setConnected(false);
      }
    });

    void load().then(connect);
    return () => {
      stopped = true;
      clearTimeout(reconnect);
      clearInterval(heartbeat);
      clearInterval(poll);
      appState.remove();
      socket?.close();
    };
  }, [load]);

  return { ...state, live, refresh: load };
}

/** 종목별 24시간 가격 기록. 홈의 추이선에만 쓰므로 5분마다 다시 읽는다. */
export function usePriceHistory(codes) {
  const [history, setHistory] = useState({});
  const key = codes.join(',');
  useEffect(() => {
    if (!key) return undefined;
    let cancelled = false;
    const read = async () => {
      const entries = await Promise.all(key.split(',').map(async code => {
        try { return [code, await api(`/api/stocks/${code}/history?range=24h`)]; } catch { return [code, null]; }
      }));
      if (cancelled) return;
      setHistory(current => {
        const next = { ...current };
        for (const [code, points] of entries) if (points) next[code] = points;
        return next;
      });
    };
    void read();
    const timer = setInterval(read, 5 * 60_000);
    return () => { cancelled = true; clearInterval(timer); };
  }, [key]);
  return history;
}

const KST_OFFSET_MS = 9 * 60 * 60_000;
const DAY_MS = 24 * 60 * 60_000;
const INDEX_STEP_MS = 10 * 60_000;

/** 서버 TradingProtectionService.indexValue와 같은 식: 오늘 기준가 대비 가격(×1000)을 시가총액으로 가중 평균한다. */
export function computeIndex(stocks, priceOf = stock => stock.price) {
  let weighted = 0;
  let weight = 0;
  for (const stock of stocks) {
    const cap = Number(stock.marketCap) || 0;
    const reference = Math.max(1, Number(stock.referencePrice) || 0);
    if (!cap) continue;
    weighted += (priceOf(stock) * 1000 / reference) * cap;
    weight += cap;
  }
  return weight ? weighted / weight : null;
}

/**
 * 오늘(한국시간 0시부터) 게임주 지수 흐름. 지수 기록 API가 없어서 종목별 가격 기록으로
 * 10분 간격 지점을 다시 계산하고, 마지막 점은 실시간 시세로 계산한 현재 지수다.
 * 한 종목이라도 기록이 아직 없거나 요청이 실패했으면 null을 돌려준다. 빠진 종목을 기준가로
 * 채워 그리면 데이터가 아닌 평평한 선이 되기 때문이다. 빈 배열은 24시간 동안 체결이 없었다는 실제 값이다.
 */
export function indexTrail(stocks, history, now = Date.now()) {
  if (!stocks.length || stocks.some(stock => !Array.isArray(history[stock.code]))) return null;
  const start = Math.floor((now + KST_OFFSET_MS) / DAY_MS) * DAY_MS - KST_OFFSET_MS;
  const series = {};
  for (const stock of stocks) {
    series[stock.code] = (history[stock.code] || [])
      .map(point => ({ t: Date.parse(point.recordedAt), p: Number(point.price) }))
      .filter(point => Number.isFinite(point.t) && point.p > 0)
      .sort((a, b) => a.t - b.t);
  }
  // 시각이 앞으로만 가므로 종목마다 읽은 위치를 기억해 기록을 한 번만 훑는다.
  const cursor = {};
  const last = {};
  const priceAt = t => stock => {
    const points = series[stock.code];
    let i = cursor[stock.code] ?? 0;
    while (i < points.length && points[i].t <= t) { last[stock.code] = points[i].p; i += 1; }
    cursor[stock.code] = i;
    // 24시간 안에 체결 기록이 없던 종목은 기준가에 머물러 있던 것으로 본다.
    return last[stock.code] ?? (Number(stock.referencePrice) || stock.price);
  };
  const points = [];
  for (let t = start; t < now; t += INDEX_STEP_MS) {
    const value = computeIndex(stocks, priceAt(t));
    if (value != null) points.push({ recordedAt: new Date(t).toISOString(), price: value });
  }
  return { points, start };
}

/** 기준가 대비 등락률(비율). 서버의 changePercent와 같은 값을 가격에서 다시 계산한다. */
export const sessionRate = stock => {
  const reference = Number(stock.referencePrice) || 0;
  if (reference > 0) return (stock.price - reference) / reference;
  return (Number(stock.changePercent) || 0) / 100;
};
