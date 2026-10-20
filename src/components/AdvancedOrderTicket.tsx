import { useEffect, useId, useRef, useState } from "react";
import { won } from "../market/format";
import { useMarket, useMarketApi } from "../market/MarketProvider";
import type { OrderResult } from "../market/types";
import { requireSignIn } from "../lib/auth";
import { useTradingShortcuts } from "../lib/useTradingShortcuts";
import { nextTickPrice, previousTickPrice, roundToTick, tickSize } from "../market/universe";
import { LISTING_BY_CODE } from "../market/universe";
import { useAuthUser } from "../lib/auth";
import { GameIcon } from "./GameIcon";
import { Link } from "./Link";
import { totals } from "../market/selectors";
import { navigate, TRADE_PATH } from "../router";
import { affordableQuantity } from "../market/orderSizing";

const RATIOS = [
  { label: "10%", ratio: 0.1 },
  { label: "25%", ratio: 0.25 },
  { label: "50%", ratio: 0.5 },
  { label: "최대", ratio: 1 },
];

export function AdvancedOrderTicket({ code, initialSide = "buy", selectedLimitPrice, selectedLimitPriceRevision, onShowOrders }: { code: string; initialSide?: "buy" | "sell"; selectedLimitPrice?: number | null; selectedLimitPriceRevision?: number; onShowOrders?: () => void }) {
  const quantityId = useId();
  const priceId = useId();
  const formRef = useRef<HTMLFormElement>(null);
  const submissionPendingRef = useRef(false);
  // 사용자가 단가를 직접 고르기 전에는 최우선 호가를 따라간다. 첫 렌더 시세가 서버 시세로 바뀌어도 단가가 낡지 않는다.
  const limitPriceEditedRef = useRef(false);
  const snapshot = useMarket();
  const api = useMarketApi();
  const { user } = useAuthUser();
  const [side, setSide] = useState<"buy" | "sell">(initialSide);
  const [orderType, setOrderType] = useState<"MARKET" | "LIMIT">("LIMIT");
  const [limitPrice, setLimitPrice] = useState(() => String(roundToTick(snapshot.quotes[code]?.asks[0]?.price ?? snapshot.quotes[code]?.price ?? 1)));
  const [qty, setQty] = useState("");
  const [result, setResult] = useState<OrderResult | null>(null);
  const [shortcutMessage, setShortcutMessage] = useState("");
  const [shortcutPending, setShortcutPending] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [shortcutMode, setShortcutMode] = useState<"off" | "editing" | "active">("off");

  useEffect(() => {
    if (selectedLimitPrice === undefined || selectedLimitPrice === null) return;
    limitPriceEditedRef.current = true;
    setLimitPrice(String(roundToTick(selectedLimitPrice)));
    setOrderType("LIMIT");
    setResult(null);
  }, [selectedLimitPrice, selectedLimitPriceRevision]);

  const quote = snapshot.quotes[code];
  const liveSuggestedLimit = quote ? (side === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price) : null;

  // 수량을 입력하기 시작하면 주문을 만드는 중이므로 단가를 더 움직이지 않는다. 보고 누른 단가와
  // 접수되는 단가가 달라지지 않게 하기 위해서다. 주문이 끝나 수량이 비면 다시 호가를 따라간다.
  useEffect(() => {
    if (limitPriceEditedRef.current || qty !== "" || orderType !== "LIMIT" || liveSuggestedLimit === null) return;
    setLimitPrice(String(roundToTick(liveSuggestedLimit)));
  }, [liveSuggestedLimit, orderType, qty]);

  const setQuantityRatio = (ratio: number) => {
    const price = orderType === "LIMIT" && Number(limitPrice) > 0 ? Number(limitPrice) : quote?.price ?? 0;
    const maxQty = snapshot.portfolioError ? 0 : side === "buy" ? affordableQuantity(snapshot.portfolio.cash, price) : Math.min(1000, api.orderable(code, side));
    const next = Math.min(maxQty, Math.max(1, Math.floor(maxQty * ratio)));
    setQty(String(maxQty > 0 ? next : ""));
    setResult(null);
    setShortcutMessage("");
  };

  const changeSide = (nextSide: "buy" | "sell") => {
    setSide(nextSide);
    limitPriceEditedRef.current = false;
    setQty("");
    setResult(null);
    setShortcutMessage("");
    if (orderType === "LIMIT" && quote) {
      const nextSuggested = nextSide === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
      setLimitPrice(String(roundToTick(nextSuggested)));
    }
  };

  const changeOrderType = (nextOrderType: "MARKET" | "LIMIT") => {
    const orderTypeAllowed = nextOrderType === "MARKET" ? quote?.restriction?.marketOrdersAllowed : quote?.restriction?.limitOrdersAllowed;
    if (quote?.restriction && !orderTypeAllowed) {
      setShortcutMessage(`${nextOrderType === "MARKET" ? "시장가" : "지정가"} 주문이 현재 제한되어 있습니다.`);
      return;
    }
    setOrderType(nextOrderType);
    limitPriceEditedRef.current = false;
    setResult(null);
    setShortcutMessage("");
    if (nextOrderType === "LIMIT" && quote) {
      const nextSuggested = side === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
      setLimitPrice(String(roundToTick(nextSuggested)));
    }
  };

  const moveLimitPrice = (direction: "up" | "down") => {
    if (!quote) return;
    if (quote.restriction && !quote.restriction.limitOrdersAllowed) {
      setShortcutMessage("현재 지정가 주문이 제한되어 있습니다.");
      return;
    }
    const typedPrice = Number(limitPrice);
    const currentPrice = Number.isFinite(typedPrice) && typedPrice > 0 ? typedPrice : quote.price;
    const nextPrice = direction === "up" ? nextTickPrice(currentPrice) : previousTickPrice(currentPrice);
    limitPriceEditedRef.current = true;
    setOrderType("LIMIT");
    setLimitPrice(String(nextPrice));
    setResult(null);
    setShortcutMessage(`지정가 ${won(nextPrice)}로 이동했습니다.`);
  };

  const useCurrentPrice = () => {
    if (!quote || (quote.restriction && !quote.restriction.limitOrdersAllowed)) return;
    const price = roundToTick(quote.price);
    limitPriceEditedRef.current = true;
    setOrderType("LIMIT");
    setLimitPrice(String(price));
    setResult(null);
    setShortcutMessage(`현재가 ${won(price)}를 지정가에 반영했습니다.`);
  };

  const cancelOrders = async (all: boolean) => {
    if (shortcutPending) return;
    setShortcutPending(true);
    const outcome = all ? await api.cancelAllOpenOrders() : await api.cancelOpenOrder(code);
    // 상태 줄은 직전 주문 결과를 먼저 보여 주므로, 지우지 않으면 취소 결과가 가려진다.
    setResult(null);
    setShortcutMessage(outcome.message);
    setShortcutPending(false);
  };

  useTradingShortcuts({
    onBuy: () => { if (quote) void submit("buy"); },
    onSell: () => { if (quote) void submit("sell"); },
    onCancel: (all) => void cancelOrders(all),
    onMarket: () => changeOrderType("MARKET"),
    onMovePrice: moveLimitPrice,
    onQuantityPreset: (preset) => {
      const ratio = RATIOS[preset - 1]?.ratio;
      if (ratio !== undefined) setQuantityRatio(ratio);
    },
  }, formRef, submitting || shortcutPending);

  if (!quote) return null;

  const restriction = quote.restriction;
  const blocked = Boolean(restriction && (orderType === "MARKET" ? !restriction.marketOrdersAllowed : !restriction.limitOrdersAllowed));
  const parsed = Number(qty);
  const valid = Number.isFinite(parsed) && Number.isInteger(parsed) && parsed >= 1 && parsed <= 1000;
  const suggestedLimit = side === "buy" ? quote.asks[0]?.price ?? quote.price : quote.bids[0]?.price ?? quote.price;
  const parsedLimitPrice = Number(limitPrice);
  const validLimitPrice = Number.isFinite(parsedLimitPrice) && Number.isInteger(parsedLimitPrice) && parsedLimitPrice > 0 && roundToTick(parsedLimitPrice) === parsedLimitPrice;
  const executionPrice = orderType === "MARKET" ? quote.price : (validLimitPrice ? parsedLimitPrice : suggestedLimit);
  const amount = valid ? parsed * executionPrice : 0;
  const maxQty = snapshot.portfolioError ? 0 : side === "buy" ? affordableQuantity(snapshot.portfolio.cash, executionPrice) : Math.min(1000, api.orderable(code, side));
  const affordable = parsed <= maxQty;
  const quantityError = qty === "" ? "" : !valid ? "수량은 1~1,000주 사이의 정수로 입력해 주세요." : !affordable ? `주문 가능 수량은 최대 ${maxQty.toLocaleString()}주입니다.` : "";
  const priceError = orderType === "LIMIT" && !validLimitPrice ? "단가를 해당 가격대의 호가 단위에 맞춰 입력해 주세요." : "";
  // 서버 한도(순자산의 50% − 잔액)를 화면 시세로 어림한다. 실제 한도는 대출 화면에서 서버가 다시 계산한다.
  const money = totals(snapshot);
  const loanRoom = Math.max(0, Math.floor(money.net * 0.5) - money.loan);
  const cashShort = side === "buy" && ((valid && !affordable) || result?.message === "매수 주문 가능 금액이 부족합니다.");
  const sideLabel = side === "buy" ? "매수" : "매도";
  const readyMessage = valid && affordable && !blocked && !priceError
    ? (orderType === "MARKET" ? `시장가로 ${parsed.toLocaleString()}주를 ${sideLabel}합니다.` : `${won(parsedLimitPrice)}에 ${parsed.toLocaleString()}주를 ${sideLabel}합니다.`)
    : null;

  const submit = async (orderSide: "buy" | "sell" = side) => {
    if (submissionPendingRef.current || shortcutPending) return;
    setShortcutMessage("");
    const error = snapshot.portfolioError ?? (blocked ? "현재 해당 주문 유형의 거래가 제한되어 있습니다."
      : !valid ? "주문할 수량을 1~1,000주 사이의 정수로 입력해 주세요."
      : orderType === "LIMIT" && !validLimitPrice ? "올바른 호가 단가를 입력해 주세요."
      : orderSide === "buy" && parsed > affordableQuantity(snapshot.portfolio.cash, executionPrice) ? "수수료를 포함한 매수 주문 가능 금액이 부족합니다."
      : orderSide === "sell" && parsed > api.orderable(code, "sell") ? "매도 가능한 보유 수량이 부족합니다."
      : null);
    if (error) {
      setResult({ ok: false, message: error });
      return;
    }
    if (!requireSignIn(`/market/${code}`)) return;
    submissionPendingRef.current = true;
    setSide(orderSide);
    setSubmitting(true);
    setResult(null);
    try {
      const outcome = await api.placeOrder({
      code,
      side: orderSide,
      qty: parsed,
      orderType,
      ...(orderType === "LIMIT" ? { price: parsedLimitPrice } : {}),
    });
      setResult(outcome);
      if (outcome.ok) setQty("");
    } catch {
      setResult({ ok: false, message: "주문을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요." });
    } finally {
      submissionPendingRef.current = false;
      setSubmitting(false);
    }
  };

  const statusMessage = shortcutPending ? "취소 처리 중…" : result?.message || shortcutMessage || (!user ? "로그인 후 주문할 수 있습니다." : readyMessage ?? "");

  return (
    <form ref={formRef} tabIndex={-1} className={`ticket terminal-ticket is-${side}`} aria-label="주문 입력" aria-busy={submitting}
      onFocusCapture={(event) => {
        const target = event.target;
        setShortcutMode(target.matches("input, select, textarea, [contenteditable=true]") ? "editing" : target.matches("summary") ? "off" : "active");
      }}
      onBlurCapture={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setShortcutMode("off");
      }}
      onClick={(event) => {
        if (event.target instanceof Element && !event.target.closest("button, input, select, textarea, label, a, summary, [contenteditable=true]")) {
          event.currentTarget.focus({ preventScroll: true });
        }
      }}
      onSubmit={(event) => {
        event.preventDefault();
        if (valid && affordable && !blocked && !submitting && (orderType !== "LIMIT" || validLimitPrice)) void submit();
      }}>
      <div className="terminal-tabs" role="group" aria-label="주문 방향">
        {(["buy", "sell"] as const).map((option) => (
          <button key={option} type="button" className={`terminal-tab is-${option}${side === option ? " is-active" : ""}`}
            aria-pressed={side === option} onClick={() => changeSide(option)}>
            {option === "buy" ? "매수" : "매도"}
          </button>
        ))}
        {onShowOrders && <button type="button" className="terminal-tab terminal-orders-link" onClick={onShowOrders}>미체결 / 취소</button>}
      </div>

      <details className={`terminal-shortcuts${shortcutMode === "active" && !submitting && !shortcutPending ? " is-active" : ""}`}>
        <summary className="terminal-shortcuts-head">
          <strong>주문 단축키</strong>
          <span role="status">{submitting || shortcutPending ? "처리 중" : shortcutMode === "active" ? "활성" : shortcutMode === "editing" ? "입력 중" : "대기"}</span>
        </summary>
        <div className="terminal-shortcuts-body">
          <button type="button" onClick={() => formRef.current?.focus({ preventScroll: true })} disabled={submitting || shortcutPending}>단축키 사용</button>
          <div className="terminal-shortcuts-keys" aria-label="주문 단축키 안내">
            <span><kbd>B</kbd> 매수 주문</span><span><kbd>S</kbd> 매도 주문</span><span><kbd>M</kbd> 시장가</span>
            <span><kbd>↑↓</kbd> 호가</span><span><kbd>1~4</kbd> 수량 비율</span>
            <span><kbd>C</kbd> 현재 종목 1건 취소</span><span><kbd>Shift+C</kbd> 전체 취소</span>
          </div>
        </div>
      </details>

      <div className="terminal-ticket-body">
        <div className="terminal-account-line"><span>{user?.displayName || (user ? "내 모의투자 계좌" : "모의투자 계좌")}</span></div>
        <div className="terminal-symbol">
          <GameIcon code={code} size="small" />
          <select aria-label="거래 종목" value={code} onChange={(event) => navigate(TRADE_PATH(event.target.value))}>
            {snapshot.codes.map((item) => <option key={item} value={item}>{LISTING_BY_CODE[item]?.name ?? item}</option>)}
          </select>
          <span className="terminal-symbol-code">{code}</span>
        </div>
        <div className="terminal-field terminal-type-field">
          <span className="terminal-field-label" id={`${priceId}-type-label`}>유형</span>
          <div className="terminal-type-tabs" role="group" aria-labelledby={`${priceId}-type-label`}>
            <button type="button" className={`terminal-type-tab${orderType === "LIMIT" ? " is-active" : ""}`}
              aria-pressed={orderType === "LIMIT"} disabled={Boolean(restriction && !restriction.limitOrdersAllowed)}
              onClick={() => changeOrderType("LIMIT")}>지정가</button>
            <button type="button" className={`terminal-type-tab${orderType === "MARKET" ? " is-active" : ""}`}
              aria-pressed={orderType === "MARKET"} disabled={Boolean(restriction && !restriction.marketOrdersAllowed)}
              onClick={() => changeOrderType("MARKET")}>시장가</button>
          </div>
        </div>
        {restriction && <p className="ticket-restriction" role="status"><strong>{restriction.label}</strong> {restriction.effect}</p>}
        <div className="terminal-buying-power"><span>{side === "buy" ? "주문 가능 금액" : "매도 가능 수량"}</span><strong className="num">{user ? (side === "buy" ? won(snapshot.portfolio.cash) : `${maxQty.toLocaleString()}주`) : "로그인 필요"}</strong></div>
        {user && side === "buy" && (loanRoom >= 10_000 || money.loan > 0) && (
          <p className="terminal-loan-hint">
            <span>{money.loan > 0 ? <>대출 잔액 <span className="num">{won(money.loan)}</span> · </> : null}대출 가능 <span className="num">{won(loanRoom)}</span></span>
            {cashShort && loanRoom >= 10_000 && <Link to="/loan">대출받기 →</Link>}
          </p>
        )}

        <div className="terminal-field">
          <label htmlFor={quantityId}>수량 <small>1~1,000주</small></label>
          <div className="terminal-quantity-control">
            <button type="button" className="terminal-small-button" onClick={() => setQuantityRatio(1)} disabled={maxQty === 0}>최대</button>
            <div className="ticket-input">
              <input id={quantityId} type="number" inputMode="numeric" min={1} max={1000} step={1} value={qty} placeholder="0"
                aria-invalid={Boolean(quantityError)} aria-describedby={`${quantityId}-hint`}
                onChange={(event) => { setQty(event.target.value); setResult(null); setShortcutMessage(""); }} />
              <span className="ticket-unit">주</span>
            </div>
          </div>
        </div>
        <p id={`${quantityId}-hint`} className={`terminal-field-hint${quantityError ? " is-error" : ""}`} aria-live="polite">{quantityError}</p>
        <div className="terminal-presets" aria-label="주문 수량 비율">
          {/* "최대"는 수량 칸 옆 버튼과 겹치므로 비율 줄에서는 뺀다. 단축키는 RATIOS 전체를 그대로 쓴다. */}
          {RATIOS.filter((item) => item.ratio < 1).map((item) => <button key={item.label} type="button" onClick={() => setQuantityRatio(item.ratio)} disabled={maxQty === 0}>{item.label}</button>)}
        </div>
        <div className="terminal-field">
          <label htmlFor={priceId}>단가</label>
          <div className="terminal-price-control">
            <button className="terminal-small-button terminal-current-price" type="button"
              disabled={submitting || Boolean(restriction && !restriction.limitOrdersAllowed)}
              onClick={useCurrentPrice}>현재가</button>
            <div className="ticket-input terminal-price-input">
              <input id={priceId} type="number" inputMode="numeric" min={tickSize(orderType === "LIMIT" ? Number(limitPrice) : quote.price)}
                step={tickSize(Number(limitPrice) > 0 ? Number(limitPrice) : quote.price)}
                disabled={orderType === "MARKET"} value={orderType === "MARKET" ? quote.price : limitPrice}
                aria-invalid={Boolean(priceError)} aria-describedby={`${priceId}-hint`}
                onKeyDown={(event) => { if (!event.nativeEvent.isComposing && !event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey && (event.key === "ArrowUp" || event.key === "ArrowDown")) { event.preventDefault(); moveLimitPrice(event.key === "ArrowUp" ? "up" : "down"); } }}
                onChange={(event) => { limitPriceEditedRef.current = true; setLimitPrice(event.target.value); setResult(null); setShortcutMessage(""); }} />
              <span className="ticket-unit">원</span>
            </div>
            <button className="terminal-step" type="button" aria-label="한 호가 낮추기" disabled={orderType === "MARKET" || blocked} onClick={() => moveLimitPrice("down")}>−</button>
            <button className="terminal-step" type="button" aria-label="한 호가 높이기" disabled={orderType === "MARKET" || blocked} onClick={() => moveLimitPrice("up")}>+</button>
          </div>
        </div>
        <p id={`${priceId}-hint`} className={`terminal-input-hint${priceError ? " is-error" : ""}`} aria-live="polite">
          {priceError || (orderType === "MARKET" ? "시장가 주문은 실제 체결 가격에 따라 금액이 달라집니다." : "")}
        </p>
        <div className="terminal-total"><span>총 주문금액</span><strong className="num">{won(amount)}</strong></div>
        <button type="submit" className={`submit-button is-${side}`} disabled={submitting || blocked || !valid || !affordable || (orderType === "LIMIT" && !validLimitPrice)}>
          {submitting ? "주문 접수 중…" : blocked ? "거래 제한 중" : side === "buy" ? "매수" : "매도"}
        </button>
      </div>
      <p className={`terminal-status${result ? (result.ok ? " is-success" : " is-error") : ""}${statusMessage ? "" : " is-idle"}`} role="status" aria-live="polite">
        {statusMessage}
      </p>
    </form>
  );
}
