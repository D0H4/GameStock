import { AuthBridge } from "./components/AuthBridge";
import { useEffect, useRef } from "react";
import { Topbar } from "./components/Topbar";
import { HomePage } from "./pages/HomePage";
import { TradePage } from "./pages/TradePage";
import { MarketPage } from "./pages/MarketPage";
import { NewsPage } from "./pages/NewsPage";
import { RankingPage } from "./pages/RankingPage";
import { MyPage } from "./pages/MyPage";
import { SettingsPage } from "./pages/SettingsPage";
import { ChargePage } from "./pages/ChargePage";
import { LoanPage } from "./pages/LoanPage";
import { TitlesPage } from "./pages/TitlesPage";
import { AdminPage } from "./pages/AdminPage";
import { LoginPage } from "./pages/LoginPage";
import { StatusBar } from "./components/StatusBar";
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
import { useNextUi } from "./next/uiMode";
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
  const nextUi = useNextUi();
  /*
   * 새 화면: 종목 상세와 거래는 하나의 터미널로 합친다(해외 거래소처럼).
   * 홈·시장 목록은 새로 짜고, 나머지 화면은 기존 화면을 새 색 체계로 감싼다.
   */
  const terminal = nextUi && (route.name === "trade" || (route.name === "market" && route.ticker !== null));
  useNxRoot(nextUi);
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
      {nextUi ? <NxTopbar route={route} /> : <Topbar route={route} />}
      <main id="content" className={nextUi ? `content nx-content${terminal ? "" : " nx-page"}` : "content"} ref={main} tabIndex={-1}>
        {suspendedUntil ? <SuspensionNotice until={suspendedUntil} reason={profile?.tradeBanReason} /> : <>
        {route.name === "home" && (nextUi ? <HomeBoard /> : <HomePage />)}
        {route.name === "market" && (terminal ? <TradeTerminal ticker={route.ticker} /> : nextUi ? <MarketBoard /> : <MarketPage ticker={route.ticker} />)}
        {route.name === "trade" && (terminal ? <TradeTerminal ticker={route.ticker} /> : <TradePage ticker={route.ticker} />)}
        {route.name === "news" && (nextUi ? <NxNewsPage id={route.id} /> : <NewsPage id={route.id} />)}
        {route.name === "ranking" && (nextUi ? <NxRankingPage /> : <RankingPage />)}
        {PRIVATE_ROUTES.has(route.name) && !auth.user && <p className="empty" role="status">로그인 상태를 확인하고 있어요.</p>}
        {route.name === "mypage" && auth.user && (nextUi ? <NxMyPage /> : <MyPage />)}
        {route.name === "settings" && auth.user && (nextUi ? <NxSettingsPage /> : <SettingsPage />)}
        {route.name === "titles" && auth.user && (nextUi ? <NxTitlesPage /> : <TitlesPage />)}
        {route.name === "charge" && auth.user && (nextUi ? <NxChargePage /> : <ChargePage />)}
        {route.name === "loan" && auth.user && (nextUi ? <NxLoanPage /> : <LoanPage />)}
        {route.name === "admin" && auth.user && (nextUi ? <NxAdminPage /> : <AdminPage />)}
        {route.name === "login" && (nextUi ? <NxLoginPage method={route.method} /> : <LoginPage method={route.method} />)}
        </>}
      </main>
      {nextUi ? <NxStatus /> : <StatusBar />}
      <MissionRewardToast />
      <TitleWatcher />
      <LoginModal />
      <SiteNoticeDialog />
      <CircuitBreakerNotice />
    </div>
  );
}
