import { useEffect, useId, useState } from "react";
import type { FormEvent } from "react";
import { apiFetch } from "../../lib/api";
import { fillTime, serverTimestamp, won } from "../../market/format";
import { useMarket } from "../../market/MarketProvider";
import { AnimatedNav } from "../AnimatedNav";

type Kind = "STOP_LOSS" | "TAKE_PROFIT";
type StopOrder = {
  id: number; stockCode: string; kind: Kind; triggerPrice: number; quantity: number;
  status: "ACTIVE" | "FIRING" | "TRIGGERED" | "FAILED" | "CANCELLED"; message: string; createdAt: string; triggeredAt: string | null;
};

const KIND_LABEL: Record<Kind, string> = { STOP_LOSS: "손절", TAKE_PROFIT: "익절" };
const STATUS_LABEL: Record<StopOrder["status"], string> = { ACTIVE: "감시 중", FIRING: "주문 중", TRIGGERED: "실행됨", FAILED: "실행 못 함", CANCELLED: "취소됨" };

/**
 * 손절·익절 자동 매도. 현재가가 감시 가격에 닿으면 서버가 시장가 매도를 낸다.
 * 잔고가 있는 종목에서만 걸 수 있고, 목록은 지금 보고 있는 종목 것만 보인다.
 */
export function StopOrders({ code, uid }: { code: string; uid: string }) {
  const snapshot = useMarket();
  const formId = useId();
  const quote = snapshot.quotes[code];
  const held = snapshot.portfolio.positions[code]?.qty ?? 0;
  const [orders, setOrders] = useState<StopOrder[] | null>(null);
  const [kind, setKind] = useState<Kind>("STOP_LOSS");
  const [price, setPrice] = useState("");
  const [quantity, setQuantity] = useState("");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ text: string; error: boolean } | null>(null);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const next = await apiFetch<StopOrder[]>("/api/stop-orders");
        if (active) setOrders(next);
      } catch (cause) {
        if (active) setNote({ text: cause instanceof Error ? cause.message : "자동 매도 목록을 불러오지 못했어요.", error: true });
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 5000);
    return () => { active = false; window.clearInterval(timer); };
  }, [uid, revision]);

  useEffect(() => { setPrice(""); setQuantity(""); setNote(null); }, [code]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setNote(null);
    try {
      await apiFetch("/api/stop-orders", {
        method: "POST",
        body: JSON.stringify({ stockCode: code, kind, triggerPrice: Number(price), quantity: Number(quantity) }),
      });
      setNote({ text: `${KIND_LABEL[kind]} ${Number(quantity).toLocaleString("ko-KR")}주를 ${won(Number(price))}에 걸었어요.`, error: false });
      setPrice("");
      setQuantity("");
      setRevision((current) => current + 1);
    } catch (cause) {
      setNote({ text: cause instanceof Error ? cause.message : "자동 매도를 걸지 못했어요.", error: true });
    } finally {
      setBusy(false);
    }
  };

  const cancel = async (id: number) => {
    setBusy(true);
    try {
      await apiFetch(`/api/stop-orders/${id}`, { method: "DELETE" });
      setNote({ text: "자동 매도를 취소했어요.", error: false });
      setRevision((current) => current + 1);
    } catch (cause) {
      setNote({ text: cause instanceof Error ? cause.message : "취소하지 못했어요.", error: true });
    } finally {
      setBusy(false);
    }
  };

  const mine = (orders ?? []).filter((order) => order.stockCode === code);
  const current = quote?.price ?? 0;
  const hint = kind === "STOP_LOSS"
    ? `현재가 ${won(current)}보다 낮게 정하면, 그 가격 이하로 내려갈 때 시장가로 팔아요.`
    : `현재가 ${won(current)}보다 높게 정하면, 그 가격 이상으로 오를 때 시장가로 팔아요.`;

  return (
    <div className="nx-stops">
      {held > 0 ? (
        <form className="nx-stops-form" onSubmit={(event) => void submit(event)} aria-describedby={`${formId}-hint`}>
          <AnimatedNav as="div" fill activeKey={kind} className="nx-seg" role="group" aria-label="자동 매도 종류">
            {(["STOP_LOSS", "TAKE_PROFIT"] as const).map((value) => (
              <button key={value} type="button" className={kind === value ? "is-active" : undefined} aria-pressed={kind === value} onClick={() => setKind(value)}>
                {KIND_LABEL[value]}
              </button>
            ))}
          </AnimatedNav>
          <label>
            <span>감시 가격</span>
            <input className="nx-num" inputMode="numeric" value={price} placeholder={String(current)}
              onChange={(event) => setPrice(event.target.value.replace(/\D/g, ""))} required />
          </label>
          <label>
            <span>수량 <small className="nx-muted">보유 {held.toLocaleString("ko-KR")}주</small></span>
            <input className="nx-num" inputMode="numeric" value={quantity} placeholder={String(held)}
              onChange={(event) => setQuantity(event.target.value.replace(/\D/g, ""))} required />
          </label>
          <button type="submit" className="nx-btn is-primary" disabled={busy || !price || !quantity}>{KIND_LABEL[kind]} 걸기</button>
          <p id={`${formId}-hint`} className="nx-stops-hint nx-muted">{hint} 체결가는 감시 가격과 다를 수 있어요.</p>
        </form>
      ) : (
        <p className="nx-stops-hint nx-muted">이 종목을 갖고 있을 때 손절·익절 가격을 걸어 둘 수 있어요.</p>
      )}

      {note && <p className={`nx-acct-status${note.error ? " is-error" : ""}`} role="status">{note.text}</p>}

      {orders === null && !note?.error && <p className="nx-stops-hint nx-muted" role="status">걸어 둔 자동 매도를 불러오고 있어요.</p>}

      {mine.length > 0 && (
        <table className="nx-grid-table">
          <caption className="vh">이 종목의 자동 매도</caption>
          <thead><tr>{["종류", "감시 가격", "수량", "상태", ""].map((label, index) => <th key={`${label}${index}`} scope="col">{label}</th>)}</tr></thead>
          <tbody>
            {mine.map((order) => (
              <tr key={order.id}>
                <th scope="row">{KIND_LABEL[order.kind]}</th>
                <td className="nx-num">{won(order.triggerPrice)}</td>
                <td className="nx-num">{order.quantity.toLocaleString("ko-KR")}주</td>
                <td>
                  {STATUS_LABEL[order.status]}
                  {order.triggeredAt && <small className="nx-muted nx-num"> {fillTime(serverTimestamp(order.triggeredAt))}</small>}
                  {order.status !== "ACTIVE" && order.message && <small className="nx-stops-message">{order.message}</small>}
                </td>
                <td>{order.status === "ACTIVE" && <button type="button" className="nx-btn is-small" disabled={busy} onClick={() => void cancel(order.id)}>취소</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
