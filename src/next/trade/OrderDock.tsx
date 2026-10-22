import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { useLoginPrompt } from "../../lib/auth";

const MOBILE_QUERY = "(max-width: 960px)";

/** One ticket stays mounted across desktop, mobile and modal open/close. */
export function OrderDock({ title, quote, open, onOpenChange, onAfterClose, children }: {
  title: string;
  quote: ReactNode;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAfterClose?: () => void;
  children: ReactNode;
}) {
  const [mobile, setMobile] = useState(() => window.matchMedia(MOBILE_QUERY).matches);
  const dialog = useRef<HTMLDialogElement>(null);
  const heading = useId();
  const loginPrompt = useLoginPrompt();

  useEffect(() => {
    const media = window.matchMedia(MOBILE_QUERY);
    const changed = () => { setMobile(media.matches); onOpenChange(false); };
    media.addEventListener("change", changed);
    return () => media.removeEventListener("change", changed);
  }, [onOpenChange]);

  useLayoutEffect(() => {
    const element = dialog.current;
    if (!element) return;
    // A nonmodal desktop dialog must close before becoming a mobile modal.
    if (element.open) element.close();
    if (!mobile) element.open = true;
    else if (open && !loginPrompt) element.showModal();
  }, [mobile, open, loginPrompt]);

  useEffect(() => {
    if (mobile && open && loginPrompt) onOpenChange(false);
  }, [mobile, open, loginPrompt, onOpenChange]);

  useEffect(() => {
    if (!mobile || !open || loginPrompt) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = previous; };
  }, [mobile, open, loginPrompt]);

  return (
    <>
      <aside className="nx-order-dock" aria-label={`${title} 주문`}>
        <dialog ref={dialog} className="nx-order-dialog" aria-labelledby={heading} onClose={onAfterClose}
          onCancel={(event) => { event.preventDefault(); onOpenChange(false); }}>
          <header className="nx-order-heading">
            <div><h2 id={heading}>{title} 주문</h2><span>모의 거래 · 원화</span></div>
            <button type="button" className="nx-dock-close" onClick={() => onOpenChange(false)} aria-label="주문창 닫기">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>
            </button>
          </header>
          {children}
        </dialog>
      </aside>
      <div className="nx-mobile-order">
        <div><span>{title}</span><strong className="nx-num">{quote}</strong></div>
        <button type="button" onClick={() => onOpenChange(true)} aria-haspopup="dialog">주문하기</button>
      </div>
    </>
  );
}
