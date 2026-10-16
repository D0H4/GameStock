import { useEffect, useRef, useState } from "react";
import { closeLoginPrompt, useLoginPrompt } from "../lib/auth";
import { LoginCard } from "./LoginCard";
import type { LoginMethod } from "../router";

/*
 * 로그인이 필요한 동작(관심등록, 주문, 마이페이지 등)을 만나면 화면 이동 대신
 * 현재 화면 위에 띄우는 로그인 모달. /login 페이지와 같은 카드를 재사용한다.
 */
export function LoginModal() {
  const prompt = useLoginPrompt();
  const [method, setMethod] = useState<LoginMethod>("choose");
  const cardRef = useRef<HTMLDivElement>(null);

  /* 모달을 연 버튼을 기억했다가 닫히면 그 자리로 포커스를 돌려준다. 키보드 사용자가 목록 처음부터 다시 오지 않게. */
  const opened = !!prompt;
  useEffect(() => {
    if (!opened) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setMethod("choose");
    requestAnimationFrame(() => cardRef.current?.focus({ preventScroll: true }));
    return () => {
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    };
  }, [opened]);

  useEffect(() => {
    if (!prompt) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeLoginPrompt();
      /* aria-modal 이므로 Tab이 뒤 화면으로 빠지지 않게 카드 안에서 돈다. */
      if (event.key !== "Tab" || !cardRef.current) return;
      const focusable = [...cardRef.current.querySelectorAll<HTMLElement>("a[href], button:not(:disabled), input:not(:disabled), select, textarea, [tabindex]:not([tabindex='-1'])")];
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || active === cardRef.current)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      } else if (!cardRef.current.contains(active)) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [prompt]);

  if (!prompt) return null;

  return (
    <div className="login-modal">
      <div className="login-modal-backdrop" onClick={closeLoginPrompt} />
      <div className="login-modal-card" role="dialog" aria-modal="true" aria-label="로그인" tabIndex={-1} ref={cardRef}>
        <LoginCard method={method} next={prompt.next} onDone={closeLoginPrompt} onMethodChange={setMethod} />
      </div>
    </div>
  );
}
