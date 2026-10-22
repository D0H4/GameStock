import type { DerivativeAccount } from "./derivatives";

/** Keep shared cash separate from the instruments shown in each market. */
export function productAccount(account: DerivativeAccount | null, product: "etf" | "derivatives") {
  const isEtf = product === "etf";
  const positions = account?.positions.filter((position) => isEtf === (position.kind === "ETF")) ?? [];
  const trades = account?.trades.filter((trade) => isEtf === (trade.kind === "ETF")) ?? [];
  return {
    positions,
    trades,
    positionValue: positions.reduce((sum, position) => sum + position.value, 0),
    unrealizedPnl: positions.reduce((sum, position) => sum + position.unrealizedPnl, 0),
  };
}
