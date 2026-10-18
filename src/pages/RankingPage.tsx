import { useEffect, useRef, useState, type ReactNode } from "react";
import { apiFetch } from "../lib/api";
import { useAuthUser } from "../lib/auth";
import { DEFAULT_PROFILE_AVATAR } from "../lib/profileAvatars";
import { titleColor } from "../lib/titles";

type RankingEntry = {
  rank: number;
  nickname: string;
  profileImageUrl?: string | null;
  totalAsset: number;
  changePercent: number;
  /** 사용자가 칭호 페이지에서 장착한 칭호. 없으면 null. */
  equippedTitle?: string | null;
  equippedTitleWeekly?: boolean;
};

type WeeklyRankingEntry = Omit<RankingEntry, "changePercent"> & { weeklyReturnPercent: number };

/** 이번 주(월~일, 한국 시간) 수익률 순위. 월요일 0시 칭호 지급과 같은 기준으로 계산된다. */
type WeeklyRanking = {
  weekStart: string;
  weekEnd: string;
  /** 다음 칭호 지급 시각(ISO). */
  awardAt: string;
  participants: number;
  entries: WeeklyRankingEntry[];
  /** 로그인한 사용자가 이번 주 집계에 들어 있으면 그 행. 표에 없는 순위여도 내려온다. */
  me: WeeklyRankingEntry | null;
};

type RankingTab = "total" | "weekly";

const RANKING_CACHE_KEY = "gamestock-ranking-cache";
const WEEKLY_RANKING_CACHE_KEY = "gamestock-weekly-ranking-cache";
const RANKING_TAB_KEY = "gamestock-ranking-tab";

function readCache<T>(key: string, valid: (value: unknown) => boolean): T | null {
  try {
    const value = JSON.parse(window.localStorage.getItem(key) ?? "null");
    return valid(value) ? (value as T) : null;
  } catch {
    return null;
  }
}

function writeCache(key: string, value: unknown) {
  try { window.localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage may be disabled */ }
}

const readRankingCache = () => readCache<RankingEntry[]>(RANKING_CACHE_KEY, Array.isArray) ?? [];
const readWeeklyCache = () =>
  readCache<WeeklyRanking>(WEEKLY_RANKING_CACHE_KEY, (value) => Array.isArray((value as WeeklyRanking | null)?.entries));

function readTab(): RankingTab {
  try { return window.localStorage.getItem(RANKING_TAB_KEY) === "weekly" ? "weekly" : "total"; } catch { return "total"; }
}

const medalByRank: Record<number, { emoji: string; label: string }> = {
  1: { emoji: "🥇", label: "금메달 1위" },
  2: { emoji: "🥈", label: "은메달 2위" },
  3: { emoji: "🥉", label: "동메달 3위" },
};

const changeTone = (percent: number) => (percent > 0 ? "is-up" : percent < 0 ? "is-down" : "is-flat");
const formatChange = (percent: number) => `${percent >= 0 ? "+" : ""}${percent.toFixed(2)}%`;

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];
/** "2026-10-12" → "10.12(월)". 날짜만 다루므로 시간대 영향이 없게 UTC로 해석한다. */
function formatWeekDay(isoDate: string) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  return `${date.getUTCMonth() + 1}.${date.getUTCDate()}(${WEEKDAYS[date.getUTCDay()]})`;
}

function formatCountdown(targetIso: string, now: number) {
  const remaining = Math.max(0, new Date(targetIso).getTime() - now);
  const minutes = Math.floor(remaining / 60_000);
  const days = Math.floor(minutes / 1440);
  const hours = String(Math.floor((minutes % 1440) / 60)).padStart(2, "0");
  return `${days}일 ${hours}시간 ${String(minutes % 60).padStart(2, "0")}분`;
}

/**
 * 5초마다 다시 불러오는 랭킹 데이터. 지난번 값이 있으면 그것부터 보여 주고 뒤에서 갱신하므로
 * 로딩 문구는 처음 방문할 때만 보인다. 선택된 탭의 컴포넌트만 마운트되므로 숨은 탭은 요청하지 않는다.
 */
function usePolledRanking<T>(path: string, cacheKey: string, initial: T | null) {
  const [data, setData] = useState<T | null>(initial);
  const [loading, setLoading] = useState(initial === null);
  const [error, setError] = useState("");
  const hasData = useRef(initial !== null);

  useEffect(() => {
    let active = true;
    // 요청 중 표시는 effect마다 따로 둔다. ref로 공유하면 StrictMode가 effect를 다시 실행할 때
    // 정리된 쪽의 요청이 남아 새 effect의 첫 요청이 건너뛰어지고, 다음 주기(5초)까지 표가 비어 있다.
    let inFlight = false;

    const load = async () => {
      // 주기 요청이 느린 네트워크에서 겹치지 않도록 한 번에 하나만 보낸다.
      if (!active || inFlight) return;
      inFlight = true;
      try {
        const next = await apiFetch<T>(path);
        if (!active) return;
        // 기존 행을 유지한 채 데이터만 교체하므로 갱신 때 표가 깜빡이지 않는다.
        setData(next);
        writeCache(cacheKey, next);
        hasData.current = true;
        setError("");
      } catch {
        if (active && !hasData.current) {
          setError("랭킹을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
      } finally {
        inFlight = false;
        if (active) setLoading(false);
      }
    };

    void load();
    const refreshId = window.setInterval(() => void load(), 5000);
    return () => {
      active = false;
      window.clearInterval(refreshId);
    };
  }, [path, cacheKey]);

  return { data, loading, error };
}

function RankingRow({ entry, percent, mine = false }: { entry: Omit<RankingEntry, "changePercent">; percent: number; mine?: boolean }) {
  return (
    <tr className={mine ? "ranking-row-mine" : undefined} aria-current={mine ? "true" : undefined}>
      <th scope="row" className="ranking-page-rank num">
        <span className="ranking-rank-content">
          {medalByRank[entry.rank] ? (
            <span className="ranking-medal" role="img" aria-label={medalByRank[entry.rank].label}>
              {medalByRank[entry.rank].emoji}
            </span>
          ) : (
            <span>{entry.rank}</span>
          )}
        </span>
      </th>
      <td className="ranking-page-name">
        <span className="ranking-name-cell">
          <img
            className="ranking-avatar"
            src={entry.profileImageUrl || DEFAULT_PROFILE_AVATAR}
            alt=""
            loading="lazy"
            onError={(event) => {
              // 깨진 외부 이미지 대신 기본 아바타를 보여 준다. 대체 텍스트가 칸을 넘치지 않게 alt 는 비운다.
              if (event.currentTarget.src.endsWith(DEFAULT_PROFILE_AVATAR)) return;
              event.currentTarget.src = DEFAULT_PROFILE_AVATAR;
            }}
          />
          <span className="ranking-name-text">
            {entry.equippedTitle && (
              <span className={`ranking-title${entry.equippedTitleWeekly ? " is-weekly" : ""}`} title={entry.equippedTitle} style={{ color: titleColor(entry.equippedTitle) }}>{entry.equippedTitle}</span>
            )}
            <span className="ranking-nickname" title={entry.nickname}>{entry.nickname}</span>
          </span>
        </span>
      </td>
      <td className={`ranking-page-change num ${changeTone(percent)}`}>{formatChange(percent)}</td>
      <td className="ranking-page-asset num">
        <span className="ranking-asset-value">{Math.round(entry.totalAsset).toLocaleString("ko-KR")}원</span>
        {/* 좁은 화면에서는 수익률 칸을 숨기고 총 자산 아래에 붙여 닉네임 칸을 넓힌다. */}
        <span className={`ranking-asset-change ${changeTone(percent)}`}>{formatChange(percent)}</span>
      </td>
    </tr>
  );
}

/**
 * 표 사이 구분 줄. 좁은 화면은 수익률 칸을 숨기므로 colSpan 대신 열마다 칸을 두어
 * 숨은 열이 표 폭을 흐트러뜨리지 않게 한다. 문구는 닉네임 칸에 둔다.
 */
function SeparatorRow({ className, label }: { className: string; label: string }) {
  return (
    <tr className={className} aria-hidden="true">
      <td className="ranking-page-rank" />
      <td className="ranking-page-name"><span>{label}</span></td>
      <td className="ranking-page-change" />
      <td className="ranking-page-asset" />
    </tr>
  );
}

function RankingTable({ label, changeLabel, children }: { label: string; changeLabel: string; children: ReactNode }) {
  return (
    <div className="table-scroll">
      <table className="table integration-ranking" aria-label={label}>
        <colgroup>
          <col className="ranking-col-rank" />
          <col className="ranking-col-name" />
          <col className="ranking-col-change" />
          <col className="ranking-col-asset" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col" className="ranking-col-rank">순위</th>
            <th scope="col" className="ranking-col-name">닉네임</th>
            <th scope="col" className="ranking-col-change">{changeLabel}</th>
            <th scope="col" className="ranking-col-asset">총 자산</th>
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

function TotalRanking() {
  const { data, loading, error } = usePolledRanking<RankingEntry[]>("/api/ranking", RANKING_CACHE_KEY, (() => {
    const cached = readRankingCache();
    return cached.length > 0 ? cached : null;
  })());
  const ranking = data ?? [];

  if (loading) return <p className="empty is-inline">랭킹을 불러오는 중…</p>;
  if (error) return <p className="empty is-inline">{error}</p>;
  if (ranking.length === 0) return <p className="empty is-inline">아직 랭킹에 참여한 사용자가 없습니다.</p>;

  const displayed = [...ranking]
    .sort((left, right) => right.totalAsset - left.totalAsset)
    .map((entry, index) => ({ ...entry, rank: index + 1 }));
  return (
    <RankingTable label="전체 자산 랭킹 표" changeLabel="수익률">
      {displayed.map((entry) => (
        <RankingRow key={`${entry.rank}-${entry.nickname}`} entry={entry} percent={entry.changePercent} />
      ))}
    </RankingTable>
  );
}

function WeeklyRankingView() {
  const { user } = useAuthUser();
  const { data, loading, error } = usePolledRanking<WeeklyRanking>(
    "/api/titles/weekly-ranking", WEEKLY_RANKING_CACHE_KEY, readWeeklyCache());
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const tickId = window.setInterval(() => setNow(Date.now()), 30_000);
    return () => window.clearInterval(tickId);
  }, []);

  if (loading) return <p className="empty is-inline">주간 랭킹을 불러오는 중…</p>;
  if (error || !data) return <p className="empty is-inline">{error || "주간 랭킹을 불러오지 못했습니다."}</p>;

  const me = data.me;
  const meShown = me !== null && data.entries.some((entry) => entry.rank === me.rank);
  return (
    <>
      <p className="ranking-week-info">
        <span>{formatWeekDay(data.weekStart)} ~ {formatWeekDay(data.weekEnd)}</span>
        <span className="ranking-week-countdown">주간 정산까지 {formatCountdown(data.awardAt, now)}</span>
      </p>
      {data.entries.length === 0 ? (
        <p className="empty is-inline">이번 주에 거래한 사용자가 아직 없습니다. 첫 거래로 1위에 올라 보세요.</p>
      ) : (
        <RankingTable label="이번 주 수익률 랭킹 표" changeLabel="주간 수익률">
          {data.entries.map((entry) => (
            <RankingRow key={`${entry.rank}-${entry.nickname}`} entry={entry} percent={entry.weeklyReturnPercent}
              mine={me !== null && entry.rank === me.rank} />
          ))}
          {me && !meShown && (
            <>
              <SeparatorRow className="ranking-gap" label="⋯" />
              <RankingRow entry={me} percent={me.weeklyReturnPercent} mine />
            </>
          )}
        </RankingTable>
      )}
      <p className="ranking-week-note">
        {user && !me ? "이번 주에 한 번 이상 거래하면 순위에 들어가요. " : ""}
        이번 주에 1회 이상 거래한 사용자만 집계하며, 출석·미션 보상과 대출금은 수익률에서 제외합니다.
      </p>
    </>
  );
}

export function RankingPage() {
  const [tab, setTab] = useState<RankingTab>(readTab);

  const selectTab = (next: RankingTab) => {
    setTab(next);
    try { window.localStorage.setItem(RANKING_TAB_KEY, next); } catch { /* storage may be disabled */ }
  };

  return (
    <div className="page-stack is-compact">
      <h1 className="page-title">랭킹</h1>

      <div className="ranking-tabs" role="tablist" aria-label="랭킹 종류">
        <button type="button" role="tab" id="ranking-tab-total" aria-selected={tab === "total"} aria-controls="ranking-panel"
          className={`ranking-tab${tab === "total" ? " is-active" : ""}`} onClick={() => selectTab("total")}>전체 자산</button>
        <button type="button" role="tab" id="ranking-tab-weekly" aria-selected={tab === "weekly"} aria-controls="ranking-panel"
          className={`ranking-tab${tab === "weekly" ? " is-active" : ""}`} onClick={() => selectTab("weekly")}>이번 주 수익률</button>
      </div>

      <section id="ranking-panel" role="tabpanel" aria-labelledby={tab === "total" ? "ranking-tab-total" : "ranking-tab-weekly"}>
        {tab === "total" ? <TotalRanking /> : <WeeklyRankingView />}
      </section>
    </div>
  );
}
