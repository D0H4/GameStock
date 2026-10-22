import { useState } from "react";
import { Delta } from "../../components/Delta";
import { Sparkline } from "../../components/Sparkline";
import { indexValue, won } from "../../market/format";
import type { DerivativeAccount, DerivativeOrderRequest, DerivativeSnapshot } from "../../market/derivatives";

interface Props {
  snapshot: DerivativeSnapshot;
  account: DerivativeAccount | null;
  busy: boolean;
  onOpen: (request: DerivativeOrderRequest) => void;
  onClose: (positionId: number, quantity: number) => void;
}

/** 지수 선물. 방향·레버리지를 고르면 증거금이 즉시 계산된다. */
export function FuturesPanel({ snapshot, account, busy, onOpen, onClose }: Props) {
  const [side, setSide] = useState<"LONG" | "SHORT">("LONG");
  const [leverage, setLeverage] = useState(5);
  const [quantityInput, setQuantityInput] = useState("1");
  const future = snapshot.future;
  const positions = account?.positions.filter((position) => position.kind === "FUTURE") ?? [];
  const quantity = Number(quantityInput);
  const validQuantity = Number.isInteger(quantity) && quantity >= 1 && quantity <= 100;
  const qty = validQuantity ? quantity : 0;
  const notional = Math.round(future.price * future.multiplier * qty);
  const margin = Math.ceil(notional / leverage);
  const fee = Math.ceil(notional * 0.0002);
  const tone = future.changePercent > 0 ? "up" : future.changePercent < 0 ? "down" : "flat";

  return (
    <div className="derivative-panel">
      <section className="futures-hero">
        <div className="futures-quote">
          <span className="derivative-eyebrow">게임주 지수 선물 · 무기한</span>
          <strong className={`futures-price num is-${tone}`}>{indexValue(future.price)}</strong>
          <span className={`futures-change is-${tone}`}>
            <Delta change={future.price - future.prevClose} ratio={future.changePercent / 100} showAmount={false} />
          </span>
          <Sparkline series={future.series} prevClose={future.prevClose} tone={tone} width={240} height={44} />
        </div>
        <dl className="derivative-facts futures-facts">
          <div><dt>계약 단위</dt><dd className="num">1포인트 × {future.multiplier.toLocaleString("ko-KR")}원</dd></div>
          <div><dt>레버리지</dt><dd className="num">{future.leverages.map((item) => `${item}x`).join(" · ")}</dd></div>
          <div><dt>유지증거금</dt><dd className="num">{Math.round(future.maintenanceRate * 100)}%</dd></div>
          <div><dt>청산 기준</dt><dd>평가액 ≤ 증거금의 {Math.round(future.maintenanceRate * 100)}%</dd></div>
        </dl>
      </section>

      <section className="derivative-ticket is-wide">
        <h2>선물 주문</h2>
        <div className="futures-order-grid">
          <div className="futures-order-main">
            <div className="terminal-tabs derivative-side-tabs" role="group" aria-label="선물 방향">
              <button type="button" className={`terminal-tab is-buy${side === "LONG" ? " is-active" : ""}`}
                aria-pressed={side === "LONG"} onClick={() => setSide("LONG")}>롱 (매수)</button>
              <button type="button" className={`terminal-tab is-sell${side === "SHORT" ? " is-active" : ""}`}
                aria-pressed={side === "SHORT"} onClick={() => setSide("SHORT")}>숏 (매도)</button>
            </div>
            <div className="derivative-leverage" role="group" aria-label="레버리지">
              {future.leverages.map((item) => (
                <button type="button" key={item} className={`derivative-chip${leverage === item ? " is-active" : ""}`}
                  aria-pressed={leverage === item} onClick={() => setLeverage(item)}>{item}x</button>
              ))}
            </div>
            <label className="derivative-field">
              <span>계약 수</span>
              <input type="number" inputMode="numeric" min={1} max={100} step={1} value={quantityInput}
                onChange={(event) => setQuantityInput(event.target.value)} />
            </label>
          </div>
          <dl className="derivative-order-summary">
            <div><dt>계약 명목가</dt><dd className="num">{won(notional)}</dd></div>
            <div><dt>필요 증거금</dt><dd className="num">{won(margin)}</dd></div>
            <div><dt>수수료 0.02%</dt><dd className="num">{won(fee)}</dd></div>
            <div className="is-emphasis"><dt>즉시 차감</dt><dd className="num">{won(margin + fee)}</dd></div>
          </dl>
        </div>
        <button type="button" className={`derivative-submit is-${side === "LONG" ? "buy" : "sell"}`}
          disabled={busy || !validQuantity}
          onClick={() => onOpen({ kind: "FUTURE", side, symbol: "FUTIDX", leverage, quantity })}>
          {busy ? "처리 중…" : side === "LONG" ? "롱 포지션 열기" : "숏 포지션 열기"}
        </button>
        <p className="derivative-note">
          선물은 무기한 계약이며 만기가 없습니다. 평가손익이 증거금을 넘어서면 자동 청산되고,
          손실은 맡긴 증거금을 넘지 않습니다.
        </p>
      </section>

      <section className="derivative-card">
        <header className="derivative-card-head">
          <div><h2>보유 선물</h2><p>평가액은 증거금과 미실현 손익을 합한 값입니다.</p></div>
          <span className="derivative-badge">{positions.length}건</span>
        </header>
        {positions.length === 0 ? (
          <p className="empty">보유 중인 선물 포지션이 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="table derivative-table">
              <caption className="vh">보유 선물 포지션</caption>
              <thead>
                <tr><th scope="col">방향</th><th scope="col">레버리지</th><th scope="col" className="cell-num">계약</th>
                  <th scope="col" className="cell-num">진입 지수</th><th scope="col" className="cell-num">현재 지수</th>
                  <th scope="col" className="cell-num">증거금</th><th scope="col" className="cell-num">평가손익</th>
                  <th scope="col" className="cell-num">청산</th></tr>
              </thead>
              <tbody>
                {positions.map((position) => (
                  <tr key={position.id}>
                    <td className={position.side === "LONG" ? "is-up" : "is-down"}>
                      {position.side === "LONG" ? "롱" : "숏"} {position.leverage}x
                    </td>
                    <td className="num">{position.leverage}x</td>
                    <td className="num">{position.quantity}</td>
                    <td className="num">{indexValue(position.entryPrice)}</td>
                    <td className="num">{indexValue(position.markPrice)}</td>
                    <td className="num">{won(position.margin)}</td>
                    <td className="cell-num"><Delta change={position.unrealizedPnl} ratio={position.unrealizedPercent / 100} /></td>
                    <td className="cell-num">
                      <button type="button" className="derivative-close" disabled={busy}
                        onClick={() => onClose(position.id, position.quantity)}>전량</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
