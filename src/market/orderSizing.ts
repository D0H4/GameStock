/** Match the backend's 0.1% fee, rounded to the nearest won (minimum one). */
export function orderFee(gross: number): number {
  return gross > 0 ? Math.max(1, Math.round(gross / 1000)) : 0;
}

export function affordableQuantity(cash: number, price: number, cap = 1000): number {
  if (!Number.isFinite(cash) || !Number.isFinite(price) || cash <= 0 || price <= 0) return 0;
  let low = 0;
  let high = Math.max(0, Math.min(cap, Math.floor(cash / price)));
  while (low < high) {
    const quantity = Math.ceil((low + high) / 2);
    const gross = quantity * price;
    if (gross + orderFee(gross) <= cash) low = quantity;
    else high = quantity - 1;
  }
  return low;
}
