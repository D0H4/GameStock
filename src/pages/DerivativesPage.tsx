import { useState } from "react";
import { Delta } from "../components/Delta";
import { requireSignIn } from "../lib/auth";
import { useMarketApi } from "../market/MarketProvider";
import { closeDerivative, openDerivative, useDerivativeMarket } from "../market/derivatives";
import type { DerivativeAccount, DerivativeOrderRequest } from "../market/derivatives";
import { indexValue, kstDateTime, serverTimestamp, signedWon, won } from "../market/format";
import { navigate, type DerivativesTab } from "../router";
import { EtfPanel } from "./derivatives/EtfPanel";
import { FuturesPanel } from "./derivatives/FuturesPanel";
import { OptionsPanel } from "./derivatives/OptionsPanel";

const TABS: { id: DerivativesTab; label: string; hint: string }[] = [
  { id: "etf", label: "ETF", hint: "카테고리·지수 바스켓" },
  { id: "futures", label: "선물", hint: "지수 레버리지" },
  { id: "options", label: "옵션", hint: "10분·1시간·일요일 만기" },
];

const ACTION_LABEL: Record<string, string> = {
  OPEN: "신규",
  CLOSE: "청산",
  EXPIRE: "만기",
  LIQUIDATE: "강제청산",
};

export function DerivativesPage({ tab }: { tab: DerivativesTab }) {
  const { snapshot, account, loading, error, setAccount, user } = useDerivativeMarket();
  const stockApi = useMarketApi();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ tone: "ok" | "error"; text: string } | null>(null);

  const run = async (action: () => Promise<{ message: string; account: DerivativeAccount }>) => {
    if (!requireSignIn(`/derivatives/${tab}`)) return;
    setBusy(true);
    setNotice(null);
    try {
      const result = await action();
      setAccount(result.account);
      setNotice({ tone: "ok", text: result.message });
      await stockApi.refreshPortfolio();
    } catch (failure) {
      setNotice({ tone: "error", text: failure instanceof Error ? failure.message : "주문 처리에 실패했습니다." });
    } finally {
      setBusy(false);
    }
  };

  const open = (request: DerivativeOrderRequest) => void run(() => openDerivative(request));
  const close = (positionId: number, quantity: number) => void run(() => closeDerivative(positionId, quantity));

  if (!snapshot) {
    return (
      <div className="page-stack derivatives-page">
        <h1 className="page-title">ETF·파생</h1>
        <p className="empty" role="status">
          {loading ? "파생 시장을 불러오는 중…" : error ?? "파생 시장을 불러오지 못했습니다."}
        </p>
      </div>
    );
  }

  return (
    <div className="page-stack derivatives-page">
      <header className="derivatives-head">
        <div>
          <p className="derivative-eyebrow">모의 파생시장 · 24시간</p>
          <h1>ETF · 선물 · 옵션</h1>
          <p className="derivatives-lede">
            게임주 바스켓 ETF, 지수 레버리지 선물, 10분·1시간·일요일 만기 옵션을 같은 모의 계좌로 거래합니다.
            실제 상품이 아닌 시뮬레이션입니다.
          </p>
        </div>
        <div className="derivatives-head-index">
          <span>게임주 지수</span>
          <strong className="num">{indexValue(snapshot.indexValue)}</strong>
          <Delta change={snapshot.indexValue - snapshot.indexPrevClose} ratio={snapshot.indexChangePercent / 100} showAmount={false} />
        </div>
      </header>

      <nav className="derivatives-tabs" aria-label="상품 종류">
        {TABS.map((item) => (
          <button type="button" key={item.id}
            className={`derivatives-tab${tab === item.id ? " is-active" : ""}`}
            aria-pressed={tab === item.id}
            onClick={() => navigate(`/derivatives/${item.id}`)}>
            <strong>{item.label}</strong>
            <span>{item.hint}</span>
          </button>
        ))}
      </nav>

      {user && account ? (
        <dl className="derivative-account" aria-label="파생 계좌 요약">
          <div><dt>사용 가능 현금</dt><dd className="num">{won(account.cash)}</dd></div>
          <div><dt>파생 평가액</dt><dd className="num">{won(account.positionValue)}</dd></div>
          <div><dt>미실현 손익</dt><dd className={`num ${account.unrealizedPnl > 0 ? "is-up" : account.unrealizedPnl < 0 ? "is-down" : ""}`}>{signedWon(account.unrealizedPnl)}</dd></div>
          <div><dt>확정 손익</dt><dd className={`num ${account.realizedPnl > 0 ? "is-up" : account.realizedPnl < 0 ? "is-down" : ""}`}>{signedWon(account.realizedPnl)}</dd></div>
          <div><dt>누적 수수료</dt><dd className="num">{won(account.totalFees)}</dd></div>
        </dl>
      ) : (
        <div className="derivative-signin">
          <p>로그인하면 파생 계좌·포지션과 주문 기능을 사용할 수 있습니다. 시세는 로그인 없이도 볼 수 있어요.</p>
          <button type="button" className="derivative-submit is-buy" onClick={() => requireSignIn(`/derivatives/${tab}`)}>
            로그인하고 거래하기
          </button>
        </div>
      )}

      {notice && (
        <p className={`derivative-notice is-${notice.tone}`} role="status">{notice.text}</p>
      )}
      {error && snapshot && <p className="derivative-notice is-error" role="status">{error} 이전 시세를 표시합니다.</p>}

      {tab === "etf" && (
        <EtfPanel snapshot={snapshot} account={account} busy={busy} onOpen={open} onClose={close} />
      )}
      {tab === "futures" && (
        <FuturesPanel snapshot={snapshot} account={account} busy={busy} onOpen={open} onClose={close} />
      )}
      {tab === "options" && (
        <OptionsPanel snapshot={snapshot} account={account} busy={busy} onOpen={open} onClose={close} />
      )}

      {account && account.trades.length > 0 && (
        <section className="derivative-card">
          <header className="derivative-card-head">
            <div><h2>최근 파생 체결</h2><p>신규·청산·만기·강제청산 기록입니다.</p></div>
            <span className="derivative-badge">{account.trades.length}건</span>
          </header>
          <div className="table-scroll">
            <table className="table derivative-table">
              <caption className="vh">최근 파생 체결</caption>
              <thead>
                <tr><th scope="col">시각</th><th scope="col">구분</th><th scope="col">상품</th>
                  <th scope="col" className="cell-num">수량</th><th scope="col" className="cell-num">가격</th>
                  <th scope="col" className="cell-num">현금 흐름</th><th scope="col" className="cell-num">수수료</th></tr>
              </thead>
              <tbody>
                {account.trades.map((trade) => (
                  <tr key={trade.id}>
                    <td className="num">{kstDateTime(serverTimestamp(trade.createdAt))}</td>
                    <td>{ACTION_LABEL[trade.action] ?? trade.action}</td>
                    <td>{trade.kind} · {trade.side}</td>
                    <td className="num">{trade.quantity}</td>
                    <td className="num">{trade.kind === "OPTION" ? `${trade.price.toFixed(2)}p` : indexValue(trade.price)}</td>
                    <td className={`cell-num ${trade.cashFlow >= 0 ? "is-up" : "is-down"}`}>{won(trade.cashFlow)}</td>
                    <td className="num">{won(trade.fee)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
