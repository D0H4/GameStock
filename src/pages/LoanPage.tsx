import { useCallback, useEffect, useId, useState, type FormEvent } from "react";
import { Panel } from "../components/Panel";
import { apiFetch } from "../lib/api";
import { useAuthUser } from "../lib/auth";
import { kstDateTime, serverTimestamp, won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import type { LoanEntry, LoanStatus } from "../market/types";

const KIND_LABEL: Record<LoanEntry["kind"], string> = {
  BORROW: "대출",
  REPAY: "상환",
  INTEREST: "이자",
  LIQUIDATION: "반대매매 상환",
};

const RATIOS = [
  { label: "10%", ratio: 0.1 },
  { label: "25%", ratio: 0.25 },
  { label: "50%", ratio: 0.5 },
  { label: "최대", ratio: 1 },
];

/** 담보비율 게이지는 100%~300% 구간을 그린다. 300%가 넘으면 꽉 찬다. */
const GAUGE_MAX = 3;
const gaugeShare = (ratio: number) => Math.max(0, Math.min(1, (ratio - 1) / (GAUGE_MAX - 1)));
const percent = (ratio: number, digits = 0) => `${(ratio * 100).toFixed(digits)}%`;
const parseAmount = (value: string) => Number(value.replace(/[^0-9]/g, ""));

/** 모의 자금 대출. 빌리고 갚는 일은 이 화면에서만 한다. */
export function LoanPage() {
  const snapshot = useMarket();
  const api = useMarketApi();
  const { user } = useAuthUser();
  const borrowId = useId();
  const repayId = useId();
  const [loan, setLoan] = useState<LoanStatus | null>(null);
  const [loadError, setLoadError] = useState("");
  const [borrowAmount, setBorrowAmount] = useState("");
  const [repayAmount, setRepayAmount] = useState("");
  const [pending, setPending] = useState<"borrow" | "repay" | null>(null);
  const [message, setMessage] = useState<{ text: string; ok: boolean } | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const next = await apiFetch<LoanStatus>("/api/loan", { signal });
      if (signal?.aborted) return;
      setLoan(next);
      setLoadError("");
    } catch (cause) {
      if (!signal?.aborted) setLoadError(cause instanceof Error ? cause.message : "대출 정보를 불러오지 못했습니다.");
    }
  }, []);

  // 한도와 담보비율은 시세에 따라 바뀌므로 10초마다 다시 읽는다.
  useEffect(() => {
    if (!user) return undefined;
    const controller = new AbortController();
    void load(controller.signal);
    const timer = window.setInterval(() => void load(controller.signal), 10_000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, [user?.uid, load]);

  const cash = snapshot.portfolio.cash;
  const balance = loan?.balance ?? snapshot.portfolio.loanBalance ?? 0;
  const repayMax = Math.min(cash, balance);
  const borrowValue = parseAmount(borrowAmount);
  const repayValue = parseAmount(repayAmount);

  const act = async (kind: "borrow" | "repay", amount: number) => {
    if (pending) return;
    setPending(kind);
    setMessage(null);
    try {
      const result = await api.loanAction(kind, amount);
      setLoan(result.loan);
      setMessage({ text: result.message, ok: true });
      if (kind === "borrow") setBorrowAmount(""); else setRepayAmount("");
    } catch (cause) {
      setMessage({ text: cause instanceof Error ? cause.message : "요청을 처리하지 못했습니다.", ok: false });
    } finally {
      setPending(null);
    }
  };

  const submitBorrow = (event: FormEvent) => {
    event.preventDefault();
    if (loan && borrowValue >= loan.minAmount && borrowValue <= loan.available) void act("borrow", borrowValue);
  };
  const submitRepay = (event: FormEvent) => {
    event.preventDefault();
    const amount = repayAmount === "" ? repayMax : repayValue;
    if (amount > 0 && amount <= repayMax) void act("repay", amount);
  };

  const borrowError = !loan || borrowAmount === "" ? ""
    : borrowValue < loan.minAmount ? `${won(loan.minAmount)}부터 빌릴 수 있습니다.`
    : borrowValue > loan.available ? `지금은 최대 ${won(loan.available)}까지 빌릴 수 있습니다.` : "";
  const repayError = repayAmount === "" ? "" : repayValue < 1 ? "갚을 금액을 입력해 주세요."
    : repayValue > repayMax ? `지금은 최대 ${won(repayMax)}까지 갚을 수 있습니다.` : "";

  const ratio = loan?.collateralRatio ?? null;
  const tone = ratio === null ? "" : loan && ratio < loan.liquidationRatio ? " is-danger" : loan && ratio < loan.warningRatio ? " is-warning" : " is-safe";

  return (
    <div className="page-stack loan-page">
      <h1 className="page-title">대출</h1>
      <p className="charge-intro">
        모의 자금을 빌려 더 크게 투자할 수 있습니다. 빌린 돈은 수익률과 랭킹에 포함되지 않고, 실제 돈은 오가지 않습니다.
      </p>

      {loadError && !loan && <p className="empty" role="alert">{loadError} <button type="button" className="text-button" onClick={() => void load()}>다시 시도</button></p>}
      {!loan && !loadError && <p className="empty" role="status">대출 정보를 불러오는 중…</p>}

      {loan && (
        <>
          {loan.warning && (
            <p className={`loan-alert${ratio !== null && ratio < loan.liquidationRatio ? " is-danger" : ""}`} role="alert">
              담보비율이 {percent(ratio ?? 0)}입니다. {percent(loan.warningRatio)} 밑에서는 새로 빌릴 수 없고,
              {" "}{percent(loan.liquidationRatio)} 밑으로 내려가면 보유 주식이 자동으로 팔려 대출을 갚습니다(반대매매).
            </p>
          )}

          <section className="loan-summary" aria-label="대출 현황">
            <dl className="loan-figures">
              <div><dt>대출 잔액</dt><dd className="num">{won(loan.balance)}</dd></div>
              <div><dt>대출 한도</dt><dd className="num">{won(loan.limit)}</dd></div>
              <div><dt>지금 빌릴 수 있는 금액</dt><dd className="num">{won(loan.available)}</dd></div>
              <div><dt>순자산</dt><dd className="num">{won(loan.netAsset)}</dd></div>
            </dl>
            <div className={`loan-gauge${tone}`}>
              <div className="loan-gauge-head">
                <span>담보비율 <small>총 자산 ÷ 대출 잔액</small></span>
                <strong className="num">{ratio === null ? "대출 없음" : percent(ratio)}</strong>
              </div>
              <div className="loan-gauge-track" aria-hidden="true">
                <span className="loan-gauge-fill" style={{ width: `${(ratio === null ? 1 : gaugeShare(ratio)) * 100}%` }} />
                <span className="loan-gauge-mark" style={{ left: `${gaugeShare(loan.liquidationRatio) * 100}%` }} />
                <span className="loan-gauge-mark" style={{ left: `${gaugeShare(loan.warningRatio) * 100}%` }} />
              </div>
              <div className="loan-gauge-scale" aria-hidden="true">
                <span>100%</span>
                <span style={{ left: `${gaugeShare(loan.liquidationRatio) * 100}%` }}>반대매매 {percent(loan.liquidationRatio)}</span>
                <span style={{ left: `${gaugeShare(loan.warningRatio) * 100}%` }}>경고 {percent(loan.warningRatio)}</span>
              </div>
            </div>
            <ul className="loan-rules">
              <li>한도는 순자산의 {percent(loan.limitRatio)}입니다.</li>
              <li>이자는 하루 {percent(loan.dailyInterestRate, 1)}이고, 한국시간 자정마다 잔액에 붙습니다.</li>
              <li>언제든 일부 또는 전액을 수수료 없이 갚을 수 있습니다.</li>
            </ul>
          </section>

          {message && (
            <p className={`account-message loan-message${message.ok ? " is-ok" : " is-error"}`} role={message.ok ? "status" : "alert"}>{message.text}</p>
          )}

          <div className="loan-forms">
            <Panel id="loan-borrow" title="빌리기" meta={`최대 ${won(loan.available)}`}>
              <form className="account-form loan-form" onSubmit={submitBorrow} noValidate>
                <label htmlFor={borrowId}>빌릴 금액</label>
                <div className="account-price-input">
                  <input id={borrowId} inputMode="numeric" autoComplete="off" placeholder={loan.borrowBlocked ? "지금은 빌릴 수 없습니다" : "금액 입력"}
                    value={borrowAmount ? borrowValue.toLocaleString("ko-KR") : ""} disabled={loan.borrowBlocked || pending !== null}
                    onChange={(event) => setBorrowAmount(event.target.value.replace(/[^0-9]/g, ""))} aria-invalid={Boolean(borrowError)} />
                  <span>원</span>
                </div>
                <div className="loan-ratios">
                  {RATIOS.map((item) => (
                    <button key={item.label} type="button" className="account-button loan-chip" disabled={loan.borrowBlocked || pending !== null}
                      onClick={() => setBorrowAmount(String(Math.floor(loan.available * item.ratio)))}>{item.label}</button>
                  ))}
                </div>
                {borrowValue > 0 && !borrowError && (
                  <p className="account-hint">하루 이자 약 {won(Math.ceil(borrowValue * loan.dailyInterestRate))}</p>
                )}
                {borrowError && <p className="account-hint is-error">{borrowError}</p>}
                <button type="submit" className="account-button is-primary"
                  disabled={loan.borrowBlocked || pending !== null || !borrowAmount || Boolean(borrowError)}>
                  {pending === "borrow" ? "처리 중…" : "대출받기"}
                </button>
              </form>
            </Panel>

            <Panel id="loan-repay" title="갚기" meta={`보유 현금 ${won(cash)}`}>
              <form className="account-form loan-form" onSubmit={submitRepay} noValidate>
                <label htmlFor={repayId}>갚을 금액 <small>비워 두면 갚을 수 있는 만큼 전부</small></label>
                <div className="account-price-input">
                  <input id={repayId} inputMode="numeric" autoComplete="off" placeholder={balance === 0 ? "갚을 대출이 없습니다" : won(repayMax)}
                    value={repayAmount ? repayValue.toLocaleString("ko-KR") : ""} disabled={balance === 0 || pending !== null}
                    onChange={(event) => setRepayAmount(event.target.value.replace(/[^0-9]/g, ""))} aria-invalid={Boolean(repayError)} />
                  <span>원</span>
                </div>
                {balance > cash && <p className="account-hint">보유 현금이 잔액보다 적습니다. 주식을 팔면 더 갚을 수 있습니다.</p>}
                {repayError && <p className="account-hint is-error">{repayError}</p>}
                <button type="submit" className="account-button is-primary"
                  disabled={balance === 0 || repayMax === 0 || pending !== null || Boolean(repayError)}>
                  {pending === "repay" ? "처리 중…" : repayAmount === "" ? "전액 상환" : "상환하기"}
                </button>
              </form>
            </Panel>
          </div>

          <Panel id="loan-history" title="대출 내역" meta={`최근 ${loan.history.length}건`}>
            {loan.history.length === 0 ? <p className="empty">아직 대출 내역이 없습니다.</p> : (
              <div className="table-scroll">
                <table className="table">
                  <caption className="vh">최근 대출·상환·이자 내역</caption>
                  <thead><tr>
                    <th scope="col">일시</th><th scope="col">구분</th>
                    <th scope="col" className="cell-num">금액</th><th scope="col" className="cell-num">남은 잔액</th>
                  </tr></thead>
                  <tbody>
                    {loan.history.map((entry) => (
                      <tr key={entry.id}>
                        <td className="num">{kstDateTime(serverTimestamp(entry.createdAt))}</td>
                        <td className={entry.kind === "BORROW" || entry.kind === "INTEREST" ? "is-up" : "is-down"}>{KIND_LABEL[entry.kind] ?? entry.kind}</td>
                        <td className="cell-num num">{won(entry.amount)}</td>
                        <td className="cell-num num">{won(entry.balanceAfter)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </>
      )}
    </div>
  );
}
