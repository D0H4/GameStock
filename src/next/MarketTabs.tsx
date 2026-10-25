import { Link } from "../components/Link";
import { AnimatedNav } from "./AnimatedNav";

export function MarketTabs({ active }: { active: "stocks" | "etf" | "futures" | "options" }) {
  const selected = active === "etf" ? "stocks" : active;
  const tabs = [
    { id: "stocks", label: "주식", to: "/market" },
    { id: "futures", label: "선물", to: "/derivatives/futures" },
    { id: "options", label: "옵션", to: "/derivatives/options" },
  ];
  return (
    <AnimatedNav animationId="products" activeKey={selected} className="nx-product-tabs" aria-label="거래 상품">
      {tabs.map(tab => <Link key={tab.id} to={tab.to} className={selected === tab.id ? "is-active" : undefined}
        aria-current={selected === tab.id ? "page" : undefined}><span className="nx-bold-reserve" data-label={tab.label}>{tab.label}</span></Link>)}
    </AnimatedNav>
  );
}
