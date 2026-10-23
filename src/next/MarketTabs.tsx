import { Link } from "../components/Link";

export function MarketTabs({ active }: { active: "stocks" | "etf" }) {
  return (
    <nav className="nx-market-tabs" aria-label="주식 상품">
      <Link to="/market" className={active === "stocks" ? "is-active" : undefined} aria-current={active === "stocks" ? "page" : undefined}>종목</Link>
      <Link to="/market/etf" className={active === "etf" ? "is-active" : undefined} aria-current={active === "etf" ? "page" : undefined}>ETF</Link>
    </nav>
  );
}
