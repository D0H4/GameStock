import { useEffect, useMemo, useRef, useState } from "react";
import { Delta } from "./Delta";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { apiFetch } from "../lib/api";
import { kstDateTime, since, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import { newsEffect } from "../market/selectors";
import type { NewsItem } from "../market/types";
import { LISTING_BY_CODE } from "../market/universe";
import { MARKET_PATH, NEWS_PATH, TRADE_PATH } from "../router";

interface Props {
  item: NewsItem;
}

const EXCERPT_PARAGRAPHS = 2;

/** 저장된 설명에서 원문 링크와 글을 나눈다. 미디어 보도는 RSS의 HTML 조각에 "출처: 주소" 줄이 붙어 온다. */
function readDescription(raw: string | undefined, title: string): { paragraphs: string[]; link: string | null } {
  if (!raw?.trim()) return { paragraphs: [], link: null };
  let link: string | null = null;
  let source = raw.replace(/출처:\s*(https?:\/\/\S+)/gi, (_, url: string) => {
    link ??= url;
    return "";
  });
  if (/<[a-z][\s\S]*>/i.test(source)) {
    const doc = new DOMParser().parseFromString(source, "text/html");
    const href = doc.querySelector("a[href]")?.getAttribute("href");
    if (!link && href && /^https?:\/\//i.test(href)) link = href;
    doc.querySelectorAll("br").forEach((br) => br.replaceWith("\n"));
    source = doc.body.textContent ?? "";
  }
  /* 제목을 되풀이할 뿐인 RSS 요약은 문단으로 치지 않는다. */
  const squeeze = (value: string) => value.replace(/[\s\-–·]+/g, "");
  const titleKey = squeeze(title).slice(0, 20);
  const paragraphs = source
    .replace(/\u00a0/g, " ")
    .split(/\n\s*\n|\r?\n/)
    .map((line) => line.replace(/[ \t]+/g, " ").trim())
    .filter((line) => line.length > 0 && !squeeze(line).startsWith(titleKey))
    .slice(0, EXCERPT_PARAGRAPHS);
  return { paragraphs, link };
}

type Excerpt = { paragraphs: string[]; url: string | null };
const excerptCache = new Map<string, Excerpt>();

/** 서버가 원문 기사에서 뽑은 첫 두 문단. 목록에 미리 실려 왔으면 그대로 쓰고, 아니면 Google News 링크가 있는 소식만 묻는다. */
function useExcerpt(link: string | null, preloaded: Excerpt | null): { excerpt: Excerpt | null; loading: boolean } {
  const [state, setState] = useState<{ link: string | null; excerpt: Excerpt | null }>(() => ({
    link,
    excerpt: link ? excerptCache.get(link) ?? null : null,
  }));
  const remote = preloaded === null && link !== null && link.startsWith("https://news.google.com/");

  useEffect(() => {
    if (!remote || !link) return;
    const cached = excerptCache.get(link);
    if (cached) {
      setState({ link, excerpt: cached });
      return;
    }
    setState({ link, excerpt: null });
    const controller = new AbortController();
    apiFetch<Excerpt>(`/api/news/excerpt?link=${encodeURIComponent(link)}`, { signal: controller.signal })
      .then((result) => {
        const excerpt = { paragraphs: (result.paragraphs ?? []).slice(0, EXCERPT_PARAGRAPHS), url: result.url ?? null };
        excerptCache.set(link, excerpt);
        setState({ link, excerpt });
      })
      .catch(() => {
        if (!controller.signal.aborted) setState({ link, excerpt: { paragraphs: [], url: null } });
      });
    return () => controller.abort();
  }, [link, remote]);

  if (preloaded) return { excerpt: preloaded, loading: false };
  const current = state.link === link ? state.excerpt : null;
  return { excerpt: current, loading: remote && current === null };
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, "");
  } catch {
    return url;
  }
}

/** 속보 오른쪽 단. 고른 소식의 원문과 발행 이후 시세 반영을 보여 준다. */
export function NewsDetail({ item }: Props) {
  const snapshot = useMarket();
  const api = useMarketApi();
  const heading = useRef<HTMLHeadingElement>(null);
  const quote = snapshot.quotes[item.code];
  const effect = item.priceChangeRatio ?? newsEffect(item.priceAtPublish, quote);
  const name = LISTING_BY_CODE[item.code]?.name ?? item.code;
  const watched = snapshot.watch.includes(item.code);
  const stored = useMemo(() => readDescription(item.description, item.title), [item.description, item.title]);
  const preloaded = useMemo<Excerpt | null>(
    () => (item.articleUrl ? { paragraphs: (item.excerpt ?? []).slice(0, EXCERPT_PARAGRAPHS), url: item.articleUrl } : null),
    [item.articleUrl, item.excerpt],
  );
  const { excerpt, loading } = useExcerpt(stored.link, preloaded);
  const paragraphs = excerpt?.paragraphs.length ? excerpt.paragraphs : loading ? [] : stored.paragraphs;
  const original = excerpt?.url ?? stored.link;

  /* 기사를 바꿀 때마다 제목으로 포커스를 옮겨 화면 낭독기가 새 본문을 읽게 한다. */
  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
    heading.current?.closest(".news-split-detail")?.scrollTo({ top: 0 });
  }, [item.id]);

  return (
    <article className="news-detail" aria-labelledby="news-detail-title">
      <header className="news-detail-head">
        <div className="news-detail-game">
          <GameIcon code={item.code} size="default" />
          <Link to={MARKET_PATH(item.code)} className="news-detail-name">{name}</Link>
          <span className="news-detail-source">{item.source}</span>
        </div>
        <Link to={NEWS_PATH(null)} className="news-detail-close" aria-label="본문 닫기">
          <span aria-hidden="true">✕</span>
        </Link>
      </header>

      <h2 id="news-detail-title" className="news-detail-title" ref={heading} tabIndex={-1}>
        {item.title}
      </h2>
      <p className="news-detail-time">
        <time dateTime={new Date(item.at).toISOString()}>{kstDateTime(item.at)}</time> · {since(item.at, snapshot.updatedAt)}
      </p>

      <dl className="news-detail-stats">
        <div>
          <dt>발행 시점</dt>
          <dd className="num">{won(item.priceAtPublish)}</dd>
        </div>
        <div>
          <dt>현재가</dt>
          <dd className="num">{quote ? won(quote.price) : "—"}</dd>
        </div>
        <div>
          <dt>발행 후 반영</dt>
          <dd><Delta change={effect} ratio={effect} showAmount={false} /></dd>
        </div>
      </dl>

      <section className="news-detail-excerpt" aria-label="기사 첫 문단" aria-busy={loading}>
        {loading && (
          <div className="news-detail-skeleton" aria-hidden="true">
            <span /><span /><span /><span /><span />
          </div>
        )}
        {paragraphs.map((paragraph, index) => (
          <p key={index}>{paragraph}</p>
        ))}
        {!loading && paragraphs.length === 0 && <p className="news-detail-empty">원문에서 본문을 가져오지 못했습니다.</p>}
        {original && !loading && (
          <a href={original} className="news-detail-origin" target="_blank" rel="noopener noreferrer">
            {excerpt?.url ? `${hostOf(excerpt.url)}에서 전체 기사 읽기` : "원문 기사 열기"} ↗
          </a>
        )}
      </section>

      <footer className="news-detail-actions">
        <Link to={MARKET_PATH(item.code)} className="account-button">종목 보기</Link>
        <Link to={TRADE_PATH(item.code)} className="account-button is-primary">주문하기</Link>
        <button type="button" className="account-button" aria-pressed={watched} onClick={() => api.toggleWatch(item.code)}>
          {watched ? "★ 관심 해제" : "☆ 관심 추가"}
        </button>
      </footer>
    </article>
  );
}
