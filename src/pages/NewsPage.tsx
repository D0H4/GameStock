import { useEffect, useRef } from "react";
import { NewsDetail } from "../components/NewsDetail";
import { Link } from "../components/Link";
import { NewsFeed } from "../components/NewsFeed";
import { useMarket } from "../market/MarketProvider";
import type { NewsItem } from "../market/types";
import { navigate, NEWS_PATH } from "../router";

export function NewsPage({ id }: { id: number | null }) {
  const snapshot = useMarket();
  const list = useRef<HTMLDivElement>(null);
  /* 실시간 갱신으로 목록에서 밀려난 기사도 읽던 중이면 계속 보여 준다. */
  const lastSeen = useRef<NewsItem | null>(null);
  const found = id === null ? null : snapshot.news.find((item) => item.id === id) ?? null;
  if (found) lastSeen.current = found;
  const selected = id === null ? null : found ?? (lastSeen.current?.id === id ? lastSeen.current : null);

  /* Esc로 닫고, ↑↓로 이웃 기사로 옮긴다. 입력칸에서는 가로채지 않는다. */
  useEffect(() => {
    if (id === null) return;
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.closest("input, textarea, select, [contenteditable='true']")) return;
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      if (event.key === "Escape") {
        event.preventDefault();
        navigate(NEWS_PATH(null));
        return;
      }
      if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
      const index = snapshot.news.findIndex((item) => item.id === id);
      const next = snapshot.news[index + (event.key === "ArrowDown" ? 1 : -1)];
      if (index < 0 || !next) return;
      event.preventDefault();
      navigate(NEWS_PATH(next.id), { replace: true });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [id, snapshot.news]);

  /* 고른 기사가 왼쪽 목록에서 보이도록 스크롤한다. */
  useEffect(() => {
    if (id === null) return;
    list.current?.querySelector(`[data-news-id="${id}"]`)?.scrollIntoView({ block: "nearest" });
  }, [id]);

  return (
    <div className="page-stack is-compact news-page">
      <h1 className="page-title">속보</h1>
      <div className={`news-split${id !== null ? " has-detail" : ""}`}>
        <div className="news-split-list" ref={list}>
          <NewsFeed compact={id !== null} selectedId={id} />
        </div>
        {selected && (
          <div className="news-split-detail">
            <Link to={NEWS_PATH(null)} className="news-split-back">← 목록</Link>
            <NewsDetail item={selected} />
          </div>
        )}
        {id !== null && !selected && (
          <div className="news-split-detail">
            <Link to={NEWS_PATH(null)} className="news-split-back">← 목록</Link>
            <p className="empty" role="status">이 소식을 찾을 수 없습니다. 목록에서 다른 소식을 골라 주세요.</p>
          </div>
        )}
      </div>
    </div>
  );
}
