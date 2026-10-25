import { useEffect, useRef } from "react";

/**
 * 마운트되는 동안 dialog를 모달로 연다. 열려 있는 동안 뒤 화면 스크롤을 막고,
 * 닫히면 스크롤과 열기 전 포커스를 되돌린다.
 */
export function useModalDialog() {
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
  return dialog;
}
