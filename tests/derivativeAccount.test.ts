import test from "node:test";
import assert from "node:assert/strict";
import { productAccount } from "../src/market/derivativeAccount";
import type { DerivativeAccount, DerivativeKind, DerivativePosition, DerivativeTrade } from "../src/market/derivatives";

const position = (id: number, kind: DerivativeKind, value: number, unrealizedPnl: number): DerivativePosition => ({
  id, kind, symbol: `${kind}${id}`, name: `${kind} ${id}`, side: "BUY", quantity: 1,
  entryPrice: value - unrealizedPnl, markPrice: value, entryValue: value - unrealizedPnl,
  margin: 0, leverage: 1, optionType: kind === "OPTION" ? "CALL" : null, strike: 0,
  expiryAt: null, value, unrealizedPnl, unrealizedPercent: 0, status: "OPEN", openedAt: "2026-10-22T00:00:00Z",
});
const trade = (id: number, kind: DerivativeKind): DerivativeTrade => ({
  id, positionId: id, kind, symbol: `${kind}${id}`, action: "OPEN", side: "BUY",
  quantity: 1, price: 1000, cashFlow: -1001, fee: 1, createdAt: "2026-10-22T00:00:00Z",
});
const account: DerivativeAccount = {
  cash: 50000, positionValue: 32000, equity: 82000, unrealizedPnl: -500, realizedPnl: 250, totalFees: 4,
  positions: [position(1, "ETF", 15000, -200), position(2, "ETF", 5000, 500), position(3, "FUTURE", 8000, -1000), position(4, "OPTION", 4000, 200)],
  trades: [trade(1, "ETF"), trade(2, "FUTURE"), trade(3, "OPTION"), trade(4, "ETF")],
};

test("stock ETF screen includes only ETF positions, history and valuation", () => {
  const result = productAccount(account, "etf");
  assert.deepEqual(result.positions.map((item) => item.id), [1, 2]);
  assert.deepEqual(result.trades.map((item) => item.id), [1, 4]);
  assert.equal(result.positionValue, 20000);
  assert.equal(result.unrealizedPnl, 300);
});

test("derivatives screen includes futures and options without double-counting ETFs", () => {
  const before = structuredClone(account);
  const result = productAccount(account, "derivatives");
  assert.deepEqual(result.positions.map((item) => item.id), [3, 4]);
  assert.deepEqual(result.trades.map((item) => item.id), [2, 3]);
  assert.equal(result.positionValue, 12000);
  assert.equal(result.unrealizedPnl, -800);
  assert.deepEqual(account, before);
});

test("an unsigned-in account has no private holdings or history in either market", () => {
  for (const product of ["etf", "derivatives"] as const) {
    assert.deepEqual(productAccount(null, product), { positions: [], trades: [], positionValue: 0, unrealizedPnl: 0 });
  }
});
