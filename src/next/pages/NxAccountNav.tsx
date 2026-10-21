import { Link } from "../../components/Link";
import { useRoute } from "../../router";

const ITEMS = [
  { to: "/mypage", label: "내 계좌", match: "mypage" },
  { to: "/charge", label: "충전하기", match: "charge" },
  { to: "/loan", label: "대출", match: "loan" },
  { to: "/titles", label: "칭호", match: "titles" },
  { to: "/settings", label: "설정", match: "settings" },
] as const;

/** 계좌 쪽 화면들이 같은 자리에서 서로 오가게 하는 줄. 계정 메뉴와 같은 목적지만 담는다. */
export function NxAccountNav() {
  const route = useRoute();
  return (
    <nav className="nx-tabs nx-account-nav" aria-label="계좌 메뉴">
      {ITEMS.map((item) => {
        const active = route.name === item.match;
        return <Link key={item.to} to={item.to} className={active ? "is-active" : undefined} aria-current={active ? "page" : undefined}>{item.label}</Link>;
      })}
    </nav>
  );
}
