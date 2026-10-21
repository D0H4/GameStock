import { AuthBridge } from "./components/AuthBridge";
import { useEffect, useRef } from "react";
import { MissionRewardToast } from "./components/MissionRewardToast";
import { TitleWatcher } from "./components/TitleWatcher";
import { LoginModal } from "./components/LoginModal";
import { CircuitBreakerNotice } from "./components/CircuitBreakerNotice";
import { SiteNoticeDialog } from "./components/SiteNoticeDialog";
import { SuspensionNotice, useSuspendedUntil } from "./components/SuspensionNotice";
import { useProfile } from "./lib/profile";
import { openLoginPrompt, useAuthUser } from "./lib/auth";
import { LISTING_BY_CODE } from "./market/universe";
import { useRoute } from "./router";
import { NxStatus, NxTopbar, TradeTerminal, useNxRoot } from "./next/TradeTerminal";
import { HomeBoard } from "./next/HomeBoard";
import { MarketBoard } from "./next/MarketBoard";
import { NxAdminPage } from "./next/pages/NxAdminPage";
import { NxChargePage } from "./next/pages/NxChargePage";
import { NxLoanPage } from "./next/pages/NxLoanPage";
import { NxLoginPage } from "./next/pages/NxLoginPage";
import { NxMyPage } from "./next/pages/NxMyPage";
import { NxNewsPage } from "./next/pages/NxNewsPage";
import { NxRankingPage } from "./next/pages/NxRankingPage";
import { NxSettingsPage } from "./next/pages/NxSettingsPage";
import { NxTitlesPage } from "./next/pages/NxTitlesPage";
import type { Route } from "./router";

const PRIVATE_ROUTES = new Set<Route["name"]>(["mypage", "settings", "titles", "charge", "loan", "admin"]);

function titleFor(route: Route): string {
  if (route.name === "market") {
    const name = route.ticker ? LISTING_BY_CODE[route.ticker]?.name : undefined;
    return name ? `${name} · 시장 · 슈엔증권` : "시장 · 슈엔증권";
  }
  if (route.name === "trade") {
    const name = route.ticker ? LISTING_BY_CODE[route.ticker]?.name : undefined;
    return name ? `${name} · 거래 · 슈엔증권` : "거래 · 슈엔증권";
  }
  if (route.name === "news") return "속보 · 슈엔증권";
  if (route.name === "ranking") return "투자 랭킹 · 슈엔증권";
  if (route.name === "mypage") return "내 계좌 · 슈엔증권";
  if (route.name === "settings") return "설정 · 슈엔증권";
  if (route.name === "titles") return "칭호 설정 · 슈엔증권";
  if (route.name === "charge") return "충전하기 · 슈엔증권";
  if (route.name === "loan") return "대출 · 슈엔증권";
  if (route.name === "admin") return "관리자 · 슈엔증권";
  if (route.name === "login") return "로그인 · 슈엔증권";
  return "슈엔증권";
}

export default function App() {
  const route = useRoute();
  const auth = useAuthUser();
  const profile = useProfile();
  const suspendedUntil = useSuspendedUntil(auth.user ? profile : null);
  /* 종목 상세와 거래는 하나의 터미널로 합친다(해외 거래소처럼). */
  const terminal = route.name === "trade" || (route.name === "market" && route.ticker !== null);
  useNxRoot();
  const main = useRef<HTMLElement>(null);
  const previous = useRef(route);

  useEffect(() => {
    if (PRIVATE_ROUTES.has(route.name) && auth.ready && !auth.user) openLoginPrompt(`/${route.name}`);
  }, [route, auth.ready, auth.user]);

  useEffect(() => {
    document.title = titleFor(route);
    /* 최초 렌더에서는 포커스를 옮기지 않는다. 이동할 때만 본문으로 보낸다. */
    if (previous.current === route) return;
    const withinNews = previous.current.name === "news" && route.name === "news";
    previous.current = route;
    /* 속보 목록에서 기사를 고를 때는 목록 스크롤과 포커스를 그대로 둔다. */
    if (withinNews) return;
    window.requestAnimationFrame(() => {
      if (document.documentElement.dataset.appShell !== "mobile") {
        main.current?.focus({ preventScroll: true });
      }
      window.scrollTo({ top: 0, left: 0, behavior: "auto" });
    });
  }, [route]);

  return (
    <div className="app">
      <a className="skip-link" href="#content">
        본문으로 건너뛰기
      </a>
      <AuthBridge />
      <NxTopbar route={route} />
      <main id="content" className={`content nx-content${terminal ? "" : " nx-page"}`} ref={main} tabIndex={-1}>
        {suspendedUntil ? <SuspensionNotice until={suspendedUntil} reason={profile?.tradeBanReason} /> : <>
        {route.name === "home" && <HomeBoard />}
        {terminal && <TradeTerminal ticker={route.name === "trade" || route.name === "market" ? route.ticker : null} />}
        {route.name === "market" && !terminal && <MarketBoard />}
        {route.name === "news" && <NxNewsPage id={route.id} />}
        {route.name === "ranking" && <NxRankingPage />}
        {PRIVATE_ROUTES.has(route.name) && !auth.user && <p className="nx-empty-state" role="status">로그인 상태를 확인하고 있어요.</p>}
        {route.name === "mypage" && auth.user && <NxMyPage />}
        {route.name === "settings" && auth.user && <NxSettingsPage />}
        {route.name === "titles" && auth.user && <NxTitlesPage />}
        {route.name === "charge" && auth.user && <NxChargePage />}
        {route.name === "loan" && auth.user && <NxLoanPage />}
        {route.name === "admin" && auth.user && <NxAdminPage />}
        {route.name === "login" && <NxLoginPage method={route.method} />}
        </>}
      </main>
      <NxStatus />
      <MissionRewardToast />
      <TitleWatcher />
      <LoginModal />
      <SiteNoticeDialog />
      <CircuitBreakerNotice />
    </div>
  );
}
