import { useEffect, useState } from "react";
import { apiFetch } from "./lib/api";

// Site notice shown once per visitor on first entry. Notices are written in the
// admin page (공지 tab) and served by /api/content. `id` changes whenever any
// visible notice is added or edited, so everyone who already confirmed sees it again.
export type SiteNoticeItem = { heading: string; body: string; code?: string };
export type SiteNotice = { id: string; items: SiteNoticeItem[] };

/**
 * Shown only when the content API cannot be reached (for example a backend
 * deployed before the admin content API). An empty list from the API means
 * "no notice" and does not fall back to this.
 */
export const SITE_NOTICE: SiteNotice | null = {
  id: "2026-10-10",
  items: [
    {
      heading: "프로모션 코드",
      body: "충전 페이지의 프로모션 코드 칸에 입력하면 특별 칭호 3종을 받을 수 있어요.",
      code: "syuen",
    },
  ],
};

/*
 * 콘텐츠 API에는 코드 칸이 없어서 본문 마지막 줄 "코드: ..."로 저장한다.
 * 관리자 화면은 이 줄을 따로 입력받아 붙이고, 공지 창은 떼어 내 코드 상자로 보여 준다.
 */
const CODE_LINE = /\n*코드:[ \t]*(\S+)[ \t]*$/;

export function splitNoticeCode(body: string): { body: string; code: string } {
  const match = body.match(CODE_LINE);
  return match ? { body: body.slice(0, match.index).trimEnd(), code: match[1] } : { body, code: "" };
}

export function joinNoticeCode(body: string, code: string): string {
  const trimmed = body.trim();
  return code.trim() ? `${trimmed}\n\n코드: ${code.trim()}` : trimmed;
}

export type NoticeRow = { id: number; title: string; body: string; updated_at?: string | number | null };

export function noticeItem(row: Pick<NoticeRow, "title" | "body">): SiteNoticeItem {
  const { body, code } = splitNoticeCode(row.body);
  return { heading: row.title, body, ...(code ? { code } : {}) };
}

/** 게시 중인 공지를 한 창에 모은다. 하나라도 바뀌면 id가 달라져 다시 보인다. */
export function noticeFromRows(rows: NoticeRow[]): SiteNotice | null {
  if (!rows.length) return null;
  return {
    id: rows.map((row) => `${row.id}:${row.updated_at ?? ""}`).join(","),
    items: rows.map(noticeItem),
  };
}

/** undefined는 아직 불러오는 중, null은 보여 줄 공지가 없다는 뜻이다. */
export function useSiteNotice(): SiteNotice | null | undefined {
  const [notice, setNotice] = useState<SiteNotice | null | undefined>(undefined);
  useEffect(() => {
    let alive = true;
    apiFetch<NoticeRow[]>("/api/content?kind=NOTICE&limit=20")
      .then((rows) => { if (alive) setNotice(noticeFromRows(rows)); })
      .catch(() => { if (alive) setNotice(SITE_NOTICE); });
    return () => { alive = false; };
  }, []);
  return notice;
}
