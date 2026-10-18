import { useCallback, useEffect, useState } from "react";
import { apiFetch } from "../../lib/api";

type Runtime = { botsPaused: boolean; marketPaused: boolean; cadenceMs: number | null; targetPerSymbol: number | null };
type Status = { runtime: Runtime; matchingEnabled: boolean; matchingOwner: boolean; metrics: Record<string, number> };
type Switch = "market" | "bots";

const POLL_MS = 30_000;

const COPY: Record<Switch, { name: string; running: string; paused: string; pauseNote: string }> = {
  market: {
    name: "시장",
    running: "시장 정상 운영 중",
    paused: "시장 일시 정지 중. 신규 주문과 체결이 막혀 있고, 주문 취소만 됩니다.",
    pauseNote: "모든 종목의 신규 주문과 체결을 막습니다. 회원은 주문 취소만 할 수 있습니다.",
  },
  bots: {
    name: "봇",
    running: "봇 정상 운영 중",
    paused: "봇 일시 정지 중. 봇 호가가 모두 빠져 있습니다.",
    pauseNote: "봇 주문을 멈추고 걸려 있던 봇 호가를 모두 취소합니다.",
  },
};

/**
 * 모든 관리 탭 위에 붙는 운영 상태 줄. 평소에는 종이 위의 한 줄이고, 시장·봇이
 * 멈췄거나 DB 저장이 막히면 전광판 면으로 바뀌어 무엇이 멈췄는지 문장으로 말한다.
 * 정지·재개는 사유를 받은 뒤에만 보낸다. 서버가 설정 전체를 교체하므로
 * 봇 주기·목표 호가는 읽어 둔 값을 그대로 돌려보낸다.
 */
export function AdminRuntime() {
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState("");
  const [editing, setEditing] = useState<Switch | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const load = useCallback(async () => {
    try {
      setStatus(await apiFetch<Status>("/api/admin/dashboard"));
      setError("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "운영 상태를 불러오지 못했습니다.");
    }
  }, []);

  useEffect(() => {
    let timer: number | undefined;
    const tick = () => {
      window.clearTimeout(timer);
      void load();
      timer = window.setTimeout(tick, POLL_MS);
    };
    const onVisibility = () => {
      if (document.hidden) window.clearTimeout(timer);
      else tick();
    };
    tick();
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [load]);

  if (!status) {
    return error ? <p className="runtime-bar is-loading" role="alert">운영 상태를 불러오지 못했습니다. {error}</p> : null;
  }

  const { runtime } = status;
  const paused: Record<Switch, boolean> = { market: runtime.marketPaused, bots: runtime.botsPaused };
  const storageBlocked = (status.metrics["persistence.storageBlocked"] ?? 0) > 0;
  const notOwner = !status.matchingEnabled || !status.matchingOwner;
  const alarm = paused.market || paused.bots || storageBlocked;

  const open = (target: Switch) => {
    setEditing(editing === target ? null : target);
    setReason("");
    setMessage("");
  };

  const submit = async () => {
    if (!editing) return;
    const next = { ...runtime, [editing === "market" ? "marketPaused" : "botsPaused"]: !paused[editing] };
    setBusy(true);
    setMessage("");
    try {
      const saved = await apiFetch<Runtime>("/api/admin/runtime", {
        method: "PUT",
        body: JSON.stringify({ ...next, reason: reason.trim() }),
      });
      setStatus({ ...status, runtime: saved });
      setMessage(`${COPY[editing].name}을 ${paused[editing] ? "재개했습니다" : "일시 정지했습니다"}.`);
      setEditing(null);
      setReason("");
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "설정을 바꾸지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className={`runtime-bar${alarm ? " is-alarm" : ""}`} aria-label="운영 상태">
      <ul className="runtime-states">
        {(["market", "bots"] as const).map((target) => (
          <li key={target} className={paused[target] ? "is-paused" : undefined}>
            <span className="runtime-state" role={paused[target] ? "alert" : undefined}>
              {paused[target] ? COPY[target].paused : COPY[target].running}
            </span>
            <button type="button" className="runtime-toggle" aria-expanded={editing === target}
              aria-label={paused[target] ? `${COPY[target].name} 재개` : `${COPY[target].name} 일시 정지`}
              onClick={() => open(target)}>
              {paused[target] ? `${COPY[target].name} 재개` : "일시 정지"}
            </button>
          </li>
        ))}
        {storageBlocked && <li className="is-paused"><span className="runtime-state" role="alert">DB 저장이 멈춰 새 주문이 쌓이고 있습니다.</span></li>}
        {notOwner && <li><span className="runtime-state">이 서버는 매칭을 맡고 있지 않습니다.</span></li>}
      </ul>

      {editing && (
        <form className="runtime-form" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
          <p className="runtime-note">
            {paused[editing] ? `${COPY[editing].name}을 다시 운영합니다.` : COPY[editing].pauseNote}
          </p>
          <label htmlFor="runtime-reason">사유 (감사 기록에 남음)</label>
          <div className="runtime-form-row">
            <input id="runtime-reason" value={reason} maxLength={300} required autoFocus
              placeholder={paused[editing] ? "예: 장애 복구 완료" : "예: 체결 오류 조사"}
              onChange={(event) => setReason(event.target.value)} />
            <button type="submit" className={`account-button${paused[editing] ? " is-primary" : " is-danger"}`} disabled={busy || !reason.trim()}>
              {paused[editing] ? `${COPY[editing].name} 재개` : `${COPY[editing].name} 일시 정지`}
            </button>
            <button type="button" className="text-button" onClick={() => setEditing(null)}>취소</button>
          </div>
        </form>
      )}
      {message && <p className="runtime-message" role="status">{message}</p>}
    </section>
  );
}
