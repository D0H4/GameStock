import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useSiteNotice, type SiteNotice, type SiteNoticeItem } from "../notice";

const SEEN_KEY = "gamestock-site-notice-seen";

function readSeen() {
  try { return localStorage.getItem(SEEN_KEY); }
  catch { return null; }
}

export function SiteNoticeDialog() {
  const [seen, setSeen] = useState(readSeen);
  const notice = useSiteNotice();
  if (!notice || seen === notice.id) return null;
  const close = () => {
    try { localStorage.setItem(SEEN_KEY, notice.id); } catch { /* Hide for this session only. */ }
    setSeen(notice.id);
  };

  return <NoticeDialog notice={notice} onClose={close} />;
}

/** 공지 창의 내용. 관리자 화면의 미리보기도 같은 마크업을 쓴다. */
export function SiteNoticeContent({ items, onClose, titleId = "site-notice-title" }: { items: SiteNoticeItem[]; onClose?: () => void; titleId?: string }) {
  return (
    <div className="circuit-notice-content">
      <p id={titleId} className="circuit-notice-kicker">📢공지사항</p>
      {items.map((item, index) => (
        <section key={`${index}:${item.heading}`} className="site-notice-item">
          <h3>{item.heading}</h3>
          <p>{item.body}</p>
          {item.code && <p className="site-notice-code">코드 <strong>{item.code}</strong></p>}
        </section>
      ))}
      {/* 미리보기(onClose 없음)에서는 누를 수 없는 그림으로만 둔다. */}
      <button type="button" className="circuit-notice-confirm" onClick={onClose} autoFocus={Boolean(onClose)}
        tabIndex={onClose ? undefined : -1} aria-hidden={onClose ? undefined : true}>확인했어요</button>
    </div>
  );
}

function NoticeDialog({ notice, onClose }: { notice: SiteNotice; onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    element.showModal();
    return () => {
      element.close();
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus({ preventScroll: true });
    };
  }, []);

  return createPortal(
    <dialog ref={dialog} className="circuit-notice site-notice" aria-labelledby="site-notice-title"
      onCancel={(event) => { event.preventDefault(); onClose(); }}>
      <SiteNoticeContent items={notice.items} onClose={onClose} />
    </dialog>, document.body,
  );
}
