import { useEffect, useState } from "react";
import { Delta } from "../../components/Delta";
import { countdown, useNow, type DerivativeAccount, type DerivativeOrderRequest, type DerivativeSnapshot, type OptionContract } from "../../market/derivatives";
import { indexValue, won } from "../../market/format";

interface Props {
  snapshot: DerivativeSnapshot;
  account: DerivativeAccount | null;
  busy: boolean;
  onOpen: (request: DerivativeOrderRequest) => void;
  onClose: (positionId: number, quantity: number) => void;
}

interface Selection {
  cycleId: "10M" | "1H" | "SUN";
  type: "CALL" | "PUT";
  strike: number;
  expiryEpoch: number;
}

const CYCLE_IDS = ["10M", "1H", "SUN"] as const;

const sameContract = (contract: OptionContract, selection: Selection) =>
  contract.cycleId === selection.cycleId && contract.type === selection.type
  && contract.strike === selection.strike && contract.expiryEpoch === selection.expiryEpoch;

/** 10분(0DTE)·1시간(주간)·일요일 18:00(월간) 만기 옵션 체인. */
export function OptionsPanel({ snapshot, account, busy, onOpen, onClose }: Props) {
  const [cycleId, setCycleId] = useState<(typeof CYCLE_IDS)[number]>("10M");
  const [selection, setSelection] = useState<Selection | null>(null);
  const [quantityInput, setQuantityInput] = useState("1");
  const now = useNow(1000);
  const cycle = snapshot.cycles.find((item) => item.id === cycleId) ?? snapshot.cycles[0];
  const contracts = cycle ? snapshot.options.filter((contract) => contract.cycleId === cycle.id) : [];
  const selected = selection ? snapshot.options.find((contract) => sameContract(contract, selection)) : undefined;
  const positions = account?.positions.filter((position) => position.kind === "OPTION") ?? [];
  const quantity = Number(quantityInput);
  const validQuantity = Number.isInteger(quantity) && quantity >= 1 && quantity <= 100;
  const premium = selected ? selected.premium * (validQuantity ? quantity : 0) : 0;
  const fee = Math.ceil(premium * 0.003);
  const strikes = [...new Set(contracts.map((contract) => contract.strike))].sort((left, right) => left - right);
  const atTheMoney = strikes.reduce((best, strike) =>
    Math.abs(strike - snapshot.indexValue) < Math.abs(best - snapshot.indexValue) ? strike : best, strikes[0] ?? 0);

  useEffect(() => {
    if (!selection) return;
    const stillListed = snapshot.options.some((contract) => sameContract(contract, selection));
    if (!stillListed) setSelection(null);
  }, [snapshot.options, selection]);

  const choose = (contract: OptionContract | undefined) => {
    if (!contract) return;
    setSelection({ cycleId: contract.cycleId as Selection["cycleId"], type: contract.type, strike: contract.strike, expiryEpoch: contract.expiryEpoch });
    setQuantityInput("1");
  };

  return (
    <div className="derivative-panel">
      <section className="option-cycles" aria-label="옵션 만기 회차">
        {snapshot.cycles.map((item) => {
          const active = item.id === cycleId;
          return (
            <button type="button" key={item.id} className={`option-cycle${active ? " is-active" : ""}`}
              aria-pressed={active} onClick={() => { setCycleId(item.id as (typeof CYCLE_IDS)[number]); setSelection(null); }}>
              <span className="option-cycle-tag">{item.tag}</span>
              <strong>{item.label}</strong>
              <span className="option-cycle-count num">{countdown(item.expiryEpoch, now)}</span>
              <span className="option-cycle-expiry">만기 {new Date(item.expiryEpoch * 1000).toLocaleString("ko-KR", { timeZone: "Asia/Seoul", month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" })}</span>
            </button>
          );
        })}
      </section>

      <section className="derivative-card">
        <header className="derivative-card-head">
          <div>
            <h2>{cycle?.label ?? "옵션"} 체인</h2>
            <p>행사가를 누르면 매수 티켓이 열립니다. 프리미엄은 1계약(지수 1포인트 × 1,000원) 기준입니다.</p>
          </div>
          <span className="derivative-badge is-live">{countdown(cycle?.expiryEpoch ?? 0, now)} 남음</span>
        </header>
        {strikes.length === 0 ? (
          <p className="empty">표시할 옵션 계약이 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="table option-chain">
              <caption className="vh">옵션 체인</caption>
              <thead>
                <tr>
                  <th scope="col" className="cell-num">콜 프리미엄</th>
                  <th scope="col" className="cell-num">행사가</th>
                  <th scope="col" className="cell-num">풋 프리미엄</th>
                </tr>
              </thead>
              <tbody>
                {strikes.map((strike) => {
                  const call = contracts.find((contract) => contract.type === "CALL" && contract.strike === strike);
                  const put = contracts.find((contract) => contract.type === "PUT" && contract.strike === strike);
                  const selectedCall = selection?.cycleId === cycleId && selection.type === "CALL" && selection.strike === strike;
                  const selectedPut = selection?.cycleId === cycleId && selection.type === "PUT" && selection.strike === strike;
                  return (
                    <tr key={strike} className={strike === atTheMoney ? "is-atm" : undefined}>
                      <td className="cell-num">
                        <button type="button" className={`option-quote is-call${selectedCall ? " is-selected" : ""}`}
                          aria-pressed={selectedCall} onClick={() => choose(call)}>
                          <span className="num">{call ? won(call.premium) : "–"}</span>
                          <small>{call ? `${call.mark.toFixed(2)}p` : ""}</small>
                        </button>
                      </td>
                      <td className="cell-num option-strike">
                        <span className="num">{indexValue(strike)}</span>
                        {strike === atTheMoney && <em>ATM</em>}
                      </td>
                      <td className="cell-num">
                        <button type="button" className={`option-quote is-put${selectedPut ? " is-selected" : ""}`}
                          aria-pressed={selectedPut} onClick={() => choose(put)}>
                          <span className="num">{put ? won(put.premium) : "–"}</span>
                          <small>{put ? `${put.mark.toFixed(2)}p` : ""}</small>
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {selected && (
        <section className="derivative-ticket is-wide">
          <h2>
            {selected.type === "CALL" ? "콜" : "풋"} {indexValue(selected.strike)} · {cycle?.label}
          </h2>
          <div className="futures-order-grid">
            <div className="futures-order-main">
              <dl className="derivative-facts">
                <div><dt>프리미엄</dt><dd className="num">{won(selected.premium)} / 계약</dd></div>
                <div><dt>손익분기</dt><dd className="num">{indexValue(selected.breakeven)}</dd></div>
                <div><dt>남은 시간</dt><dd className="num">{countdown(selected.expiryEpoch, now)}</dd></div>
              </dl>
              <label className="derivative-field">
                <span>계약 수</span>
                <input type="number" inputMode="numeric" min={1} max={100} step={1} value={quantityInput}
                  onChange={(event) => setQuantityInput(event.target.value)} />
              </label>
            </div>
            <dl className="derivative-order-summary">
              <div><dt>프리미엄 합계</dt><dd className="num">{won(premium)}</dd></div>
              <div><dt>수수료 0.3%</dt><dd className="num">{won(fee)}</dd></div>
              <div className="is-emphasis"><dt>즉시 차감</dt><dd className="num">{won(premium + fee)}</dd></div>
            </dl>
          </div>
          <button type="button" className={`derivative-submit is-${selected.type === "CALL" ? "buy" : "sell"}`}
            disabled={busy || !validQuantity}
            onClick={() => onOpen({
              kind: "OPTION", side: selected.type, quantity,
              cycleId: selected.cycleId as "10M" | "1H" | "SUN",
              strike: selected.strike, expiryEpoch: selected.expiryEpoch,
            })}>
            {busy ? "처리 중…" : `${selected.type === "CALL" ? "콜" : "풋"} 매수`}
          </button>
          <p className="derivative-note">
            옵션은 만기 시 내재가치로 현금결제됩니다. 만기 전에는 현재 프리미엄으로 청산할 수 있어요.
          </p>
        </section>
      )}

      <section className="derivative-card">
        <header className="derivative-card-head">
          <div><h2>보유 옵션</h2><p>만기 시 자동 결제되며, 그 전에는 청산할 수 있습니다.</p></div>
          <span className="derivative-badge">{positions.length}건</span>
        </header>
        {positions.length === 0 ? (
          <p className="empty">보유 중인 옵션 포지션이 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="table derivative-table">
              <caption className="vh">보유 옵션</caption>
              <thead>
                <tr><th scope="col">계약</th><th scope="col" className="cell-num">수량</th><th scope="col" className="cell-num">진입 프리미엄</th>
                  <th scope="col" className="cell-num">현재 프리미엄</th><th scope="col" className="cell-num">평가액</th>
                  <th scope="col" className="cell-num">만기</th><th scope="col" className="cell-num">평가손익</th><th scope="col" className="cell-num">청산</th></tr>
              </thead>
              <tbody>
                {positions.map((position) => (
                  <tr key={position.id}>
                    <td>{position.name}</td>
                    <td className="num">{position.quantity}</td>
                    <td className="num">{position.entryPrice.toFixed(2)}p</td>
                    <td className="num">{position.markPrice.toFixed(2)}p</td>
                    <td className="num">{won(position.value)}</td>
                    <td className="num">{position.expiryAt ? countdown(Date.parse(position.expiryAt) / 1000, now) : "–"}</td>
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
