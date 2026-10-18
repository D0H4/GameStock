import { useEffect, useState } from "react";
import { signOut } from "firebase/auth";
import { firebaseAuth } from "../lib/firebase";
import type { Profile } from "../lib/profile";

/* setTimeout은 약 24.8일을 넘기면 바로 실행되므로 그보다 긴 정지는 하루마다 다시 확인한다. */
const MAX_TIMER_MS = 24 * 60 * 60 * 1000;

/** 남은 정지의 종료 시각. 정지가 없거나 끝났으면 null. 끝나는 순간 다시 계산한다. */
export function useSuspendedUntil(profile: Profile | null): Date | null {
  const untilMs = profile?.tradeBannedUntil ? Date.parse(profile.tradeBannedUntil) : NaN;
  const [now, setNow] = useState(() => Date.now());
  const active = Number.isFinite(untilMs) && untilMs > now;
  useEffect(() => {
    if (!active) return;
    const timer = window.setTimeout(() => setNow(Date.now()), Math.min(untilMs - Date.now() + 1000, MAX_TIMER_MS));
    return () => window.clearTimeout(timer);
  }, [active, untilMs, now]);
  return active ? new Date(untilMs) : null;
}

function formatUntil(value: Date): string {
  return value.toLocaleString("ko-KR", { year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

/** 관리자가 이용을 정지한 계정은 정지가 풀릴 때까지 이 안내만 본다. 서버도 개인 기능을 모두 거절한다. */
export function SuspensionNotice({ until, reason }: { until: Date; reason?: string | null }) {
  const [busy, setBusy] = useState(false);
  const logout = async () => {
    setBusy(true);
    try {
      await signOut(firebaseAuth);
    } catch {
      window.alert("로그아웃하지 못했습니다. 다시 시도해 주세요.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="suspension" role="alert" aria-labelledby="suspension-title">
      <h1 id="suspension-title" className="suspension-title">이용이 정지된 계정입니다</h1>
      <p>{formatUntil(until)}까지 슈엔증권의 모든 서비스를 이용할 수 없습니다.</p>
      {reason && <p className="suspension-reason">사유: {reason}</p>}
      <p className="suspension-help">정지 기간이 끝나면 다시 로그인하지 않아도 자동으로 이용할 수 있습니다.</p>
      <button type="button" className="text-button" disabled={busy} onClick={() => void logout()}>로그아웃</button>
    </section>
  );
}
