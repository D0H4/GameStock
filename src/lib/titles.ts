import type { CSSProperties } from "react";
import { apiFetch } from "./api";

export type UserTitle = {
  id: string;
  name: string;
  description: string;
  category: string;
  weekly: boolean;
  owned: boolean;
  equipped: boolean;
  /** 주간 수익률 칭호의 n관왕 횟수. 일반 칭호는 1. */
  count: number;
  acquiredAt: string | null;
  updatedAt: string | null;
};

export type TitleStatus = {
  titles: UserTitle[];
  newlyAwarded: UserTitle[];
  ownedCount: number;
  totalCount: number;
};

const AWARD_EVENT = "gamestock:title-awarded";

const SPECIAL_DESCRIPTION = "프로모션 코드를 입력하면 획득 가능";
const SPECIAL_TITLES: Array<Pick<UserTitle, "id" | "name">> = [
  { id: "special_syuangel", name: "슈엔젤" },
  { id: "special_misilis_holder", name: "미실리스 대주주" },
  { id: "special_syuaga", name: "슈아가" },
];

/** 서버가 아직 특별 칭호를 내려주지 않아도 칭호 창에는 항상 미획득 상태로 보이게 한다. */
function withSpecialTitles(status: TitleStatus): TitleStatus {
  const known = new Set(status.titles.map((title) => title.id));
  const missing: UserTitle[] = SPECIAL_TITLES.filter((title) => !known.has(title.id)).map((title) => ({
    ...title,
    description: SPECIAL_DESCRIPTION,
    category: "특별",
    weekly: false,
    owned: false,
    equipped: false,
    count: 0,
    acquiredAt: null,
    updatedAt: null,
  }));
  if (missing.length === 0) return status;
  return { ...status, titles: [...status.titles, ...missing], totalCount: status.totalCount + missing.length };
}

/**
 * 칭호 이름마다 뜻에 어울리는 글씨색. 칭호 페이지와 랭킹에서 같이 쓴다.
 * 12px 글씨라 내 행의 분홍 배경(--brand-wash)에서도 4.5:1 이상인 색만 쓴다.
 */
const TITLE_COLORS: Record<string, string> = {
  "뉴비": "#477a25",
  "개미": "#7a5230",
  "소액 자산가": "#287a6f",
  "중산층 자산가": "#2f6fb5",
  "1억원의 사나이": "#297c4d",
  "10억원의 사나이": "#0f7b70",
  "30억원의 사나이": "#1f5fae",
  "50억원의 사나이": "#3346b8",
  "100억의 사나이": "#c2410c",
  "세계1위 부자": "#c93066",
  "흑우": "#1a1a1a",
  "호구": "#a6536a",
  "주식못함": "#5b6b8c",
  "큰손": "#b25209",
  "대주주": "#1e3a8a",
  "스타터": "#077894",
  "일주일 개근": "#4c7a0a",
  "한달 개근": "#117f3a",
  "개근상": "#a55a05",
  "투자 준비생": "#0274af",
  "단타충": "#d52525",
  "존버의 신": "#0f766e",
  "인생은 한방": "#be470a",
  "칭호 수집가": "#4069c9",
  "칭호 컬렉터": "#0b7b71",
  "칭호 컬렉팅 마스터": "#be185d",
  "칭호 올컬렉터": "#b91c1c",
  "슈엔젤": "#8b3fd9",
  "미실리스 대주주": "#8b3fd9",
  "슈아가": "#8b3fd9",
  "주간 수익률 3위": "#84684c",
  "주간 수익률 2위": "#6b6e73",
  "주간 수익률 1위": "#8a680f",
};

/** 칭호 이름(주간 칭호의 "n관왕" 꼬리 포함)에 맞는 글씨색. 모르는 칭호면 undefined. */
export function titleColor(label: string): string | undefined {
  return TITLE_COLORS[label] ?? TITLE_COLORS[label.replace(/\s+\d+관왕$/, "")];
}

/**
 * 새 화면용. 색표는 흰 바탕 기준이라 그대로 쓰면 다크 바탕에서 읽히지 않는다.
 * 원래 색은 --title-ink로만 넘기고, 테마별로 밝히거나 어둡게 섞는 일은 CSS(.has-title-ink)가 한다.
 */
export function titleInkStyle(label: string | null | undefined): CSSProperties | undefined {
  const color = label ? titleColor(label) : undefined;
  return color ? ({ "--title-ink": color } as CSSProperties) : undefined;
}

/** 주간 칭호는 "주간 수익률 1위 2관왕"처럼 횟수를 붙여 보여 준다. */
export function titleLabel(title: UserTitle): string {
  return title.weekly && title.owned ? `${title.name} ${title.count}관왕` : title.name;
}

/**
 * 서버가 현재 조건을 확인해 새 칭호를 지급한다. 아직 안내하지 않은 칭호는
 * 한 번만 돌아오므로 여기서 바로 알림 이벤트로 내보낸다.
 */
export async function checkTitles(signal?: AbortSignal): Promise<TitleStatus> {
  const status = withSpecialTitles(await apiFetch<TitleStatus>("/api/titles/check", { method: "POST", signal }));
  if (status.newlyAwarded.length > 0) {
    window.dispatchEvent(new CustomEvent<UserTitle[]>(AWARD_EVENT, { detail: status.newlyAwarded }));
  }
  return status;
}

/** 프로모션 코드로 특별 칭호를 받는다. 새로 받은 칭호는 바로 알림 이벤트로 내보낸다. */
export async function redeemPromoCode(code: string): Promise<TitleStatus> {
  const status = withSpecialTitles(await apiFetch<TitleStatus>("/api/titles/promo", { method: "POST", body: JSON.stringify({ code }) }));
  if (status.newlyAwarded.length > 0) {
    window.dispatchEvent(new CustomEvent<UserTitle[]>(AWARD_EVENT, { detail: status.newlyAwarded }));
  }
  return status;
}

/** 보유한 칭호를 장착한다. 빈 값을 보내면 장착을 해제한다. */
export async function equipTitle(titleId: string | null): Promise<TitleStatus> {
  return withSpecialTitles(await apiFetch<TitleStatus>("/api/titles/equipped", { method: "PUT", body: JSON.stringify({ titleId: titleId ?? "" }) }));
}

export function onTitlesAwarded(listener: (titles: UserTitle[]) => void): () => void {
  const handle = (event: Event) => listener((event as CustomEvent<UserTitle[]>).detail);
  window.addEventListener(AWARD_EVENT, handle);
  return () => window.removeEventListener(AWARD_EVENT, handle);
}
