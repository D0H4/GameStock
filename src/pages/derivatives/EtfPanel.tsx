import { useEffect, useState } from "react";
import { Sparkline } from "../../components/Sparkline";
import { Delta } from "../../components/Delta";
import { won, rate, indexValue } from "../../market/format";
import type { DerivativeAccount, DerivativeOrderRequest, DerivativeSnapshot, EtfQuote } from "../../market/derivatives";

interface Props {
  snapshot: DerivativeSnapshot;
  account: DerivativeAccount | null;
  busy: boolean;
  onOpen: (request: DerivativeOrderRequest) => void;
  onClose: (positionId: number, quantity: number) => void;
}

function tone(changePercent: number): "up" | "down" | "flat" {
  return changePercent > 0 ? "up" : changePercent < 0 ? "down" : "flat";
}

/** 카테고리별 ETF의 NAV, 구성 종목, 매수·매도 티켓, 보유 ETF를 한 화면에 보여 준다. */
export function EtfPanel({ snapshot, account, busy, onOpen, onClose }: Props) {
  const [selectedCode, setSelectedCode] = useState(snapshot.etfs[0]?.code ?? "ETFIDX");
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [quantityInput, setQuantityInput] = useState("10");
  const selected: EtfQuote | undefined = snapshot.etfs.find((etf) => etf.code === selectedCode) ?? snapshot.etfs[0];
  const holdings = account?.positions.filter((position) => position.kind === "ETF") ?? [];
  const quantity = Number(quantityInput);
  const validQuantity = Number.isInteger(quantity) && quantity >= 1 && quantity <= 100;
  const amount = selected ? Math.round(selected.price * (validQuantity ? quantity : 0)) : 0;
  const fee = Math.ceil(amount * 0.001);
  const total = side === "BUY" ? amount + fee : amount - fee;

  useEffect(() => {
    if (!snapshot.etfs.some((etf) => etf.code === selectedCode)) {
      setSelectedCode(snapshot.etfs[0]?.code ?? "ETFIDX");
    }
  }, [snapshot.etfs, selectedCode]);

  return (
    <div className="derivative-panel">
      <section className="etf-grid" aria-label="ETF 목록">
        {snapshot.etfs.map((etf) => {
          const active = selected?.code === etf.code;
          return (
            <button
              type="button"
              key={etf.code}
              className={`etf-card${active ? " is-active" : ""}`}
              aria-pressed={active}
              onClick={() => setSelectedCode(etf.code)}
            >
              <span className="etf-card-theme">{etf.theme}</span>
              <strong className="etf-card-name">{etf.name}</strong>
              <span className="etf-card-code">{etf.code}</span>
              <span className="etf-card-price num">{indexValue(etf.price)}</span>
              <span className={`etf-card-change is-${tone(etf.changePercent)}`}>
                {rate(etf.changePercent / 100)}
              </span>
              <Sparkline series={etf.series} prevClose={etf.prevClose} tone={tone(etf.changePercent)} width={132} height={28} />
            </button>
          );
        })}
      </section>

      {selected && (
        <section className="etf-detail-grid">
          <div className="derivative-card">
            <header className="derivative-card-head">
              <div>
                <h2>{selected.name}</h2>
                <p>{selected.description}</p>
              </div>
              <span className="derivative-badge">{selected.theme}</span>
            </header>
            <dl className="derivative-facts">
              <div><dt>NAV</dt><dd className="num">{indexValue(selected.price)}</dd></div>
              <div><dt>기준가</dt><dd className="num">{indexValue(selected.prevClose)}</dd></div>
              <div><dt>구성 종목</dt><dd className="num">{selected.constituents.length}개</dd></div>
              <div><dt>괴리율</dt><dd className="num">0.00%</dd></div>
            </dl>
            <div className="table-scroll">
              <table className="table etf-constituents">
                <caption className="vh">{selected.name} 구성 종목</caption>
                <thead>
                  <tr><th scope="col">종목</th><th scope="col">비중</th><th scope="col" className="cell-num">등락</th></tr>
                </thead>
                <tbody>
                  {selected.constituents.length === 0 && (
                    <tr><td colSpan={3} className="empty">이 장르에 상장된 종목이 없습니다.</td></tr>
                  )}
                  {selected.constituents.map((item) => (
                    <tr key={item.code}>
                      <td>{item.name} <span className="muted-code">{item.code}</span></td>
                      <td className="num">{(item.weight * 100).toFixed(1)}%</td>
                      <td className={`cell-num is-${tone(item.changePercent)}`}>{rate(item.changePercent / 100)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <form
            className="derivative-ticket"
            onSubmit={(event) => {
              event.preventDefault();
              if (!selected || !validQuantity) return;
              onOpen({ kind: "ETF", side, symbol: selected.code, quantity });
            }}
          >
            <h2>ETF 주문</h2>
            <div className="terminal-tabs derivative-side-tabs" role="group" aria-label="매수·매도">
              <button type="button" className={`terminal-tab is-buy${side === "BUY" ? " is-active" : ""}`}
                aria-pressed={side === "BUY"} onClick={() => setSide("BUY")}>매수</button>
              <button type="button" className={`terminal-tab is-sell${side === "SELL" ? " is-active" : ""}`}
                aria-pressed={side === "SELL"} onClick={() => setSide("SELL")}>매도</button>
            </div>
            <label className="derivative-field">
              <span>수량</span>
              <input type="number" inputMode="numeric" min={1} max={100} step={1} value={quantityInput}
                onChange={(event) => setQuantityInput(event.target.value)} />
            </label>
            <dl className="derivative-order-summary">
              <div><dt>NAV</dt><dd className="num">{selected ? indexValue(selected.price) : "–"}</dd></div>
              <div><dt>주문 금액</dt><dd className="num">{won(amount)}</dd></div>
              <div><dt>수수료 0.1%</dt><dd className="num">{won(fee)}</dd></div>
              <div className="is-emphasis"><dt>정산 예정</dt><dd className="num">{won(total)}</dd></div>
            </dl>
            <button type="submit" className={`derivative-submit is-${side === "BUY" ? "buy" : "sell"}`}
              disabled={busy || !validQuantity}>
              {busy ? "처리 중…" : side === "BUY" ? "ETF 매수" : "ETF 매도"}
            </button>
            <p className="derivative-note">ETF는 NAV로 즉시 체결되며, 보유 수량은 보유 목록에서도 청산할 수 있어요.</p>
          </form>
        </section>
      )}

      <section className="derivative-card">
        <header className="derivative-card-head">
          <div><h2>보유 ETF</h2><p>평가손익은 현재 NAV 기준입니다.</p></div>
          <span className="derivative-badge">{holdings.length}종</span>
        </header>
        {holdings.length === 0 ? (
          <p className="empty">보유 중인 ETF가 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="table derivative-table">
              <caption className="vh">보유 ETF</caption>
              <thead>
                <tr><th scope="col">상품</th><th scope="col" className="cell-num">수량</th><th scope="col" className="cell-num">평균 NAV</th>
                  <th scope="col" className="cell-num">현재 NAV</th><th scope="col" className="cell-num">평가액</th>
                  <th scope="col" className="cell-num">평가손익</th><th scope="col" className="cell-num">청산</th></tr>
              </thead>
              <tbody>
                {holdings.map((position) => (
                  <tr key={position.id}>
                    <td>{position.name}</td>
                    <td className="num">{position.quantity}</td>
                    <td className="num">{indexValue(position.entryPrice)}</td>
                    <td className="num">{indexValue(position.markPrice)}</td>
                    <td className="num">{won(position.value)}</td>
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
