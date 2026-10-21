import React, { useCallback, useEffect, useRef, useState } from 'react';
import { BackHandler, StyleSheet, View } from 'react-native';
import { useFonts } from 'expo-font';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { HomeScreen } from './src/screens/HomeScreen';
import { MenuScreen } from './src/screens/MenuScreen';
import { MarketScreen } from './src/screens/MarketScreen';
import { StockScreen } from './src/screens/StockScreen';
import { SwipeBack } from './src/components/SwipeBack';
import { NewsScreen } from './src/screens/NewsScreen';
import { RankingScreen } from './src/screens/RankingScreen';
import { NewsDetailScreen } from './src/screens/NewsDetailScreen';
import { AccountProvider } from './src/account';
import { LoginScreen } from './src/screens/LoginScreen';
import { AuthProvider, useAuth } from './src/auth';
import { TabBar, TABS } from './src/components/TabBar';
import { WebPane } from './src/WebPane';
import { newsWithIds, useMarket } from './src/market';
import { FONTS, ThemeProvider, palettes } from './src/theme';

const TAB_BY_KEY = Object.fromEntries(TABS.map(tab => [tab.key, tab]));
// 웹 화면이 보이지 않을 때 머물러 둘 공개 화면. 로그인 전용 웹 화면에 두면 로그인을 다시 요구한다.
const PARK_PATH = '/news';
/** /market/BA, /trade/BA 같은 웹 주소에서 종목 코드를 꺼낸다. */
const stockFromPath = path => String(path || '').split('/').filter(Boolean)[1]?.toUpperCase() ?? null;
/** /news/123 에서 기사 id를 꺼낸다. */
const newsFromPath = path => { const id = Number(String(path || '').split('/').filter(Boolean)[1]); return Number.isFinite(id) && id > 0 ? id : null; };

/** 웹 주소가 어느 탭에 속하는지. 홈('/')은 네이티브 홈이고, 계정 화면들은 설정 탭 메뉴에서 연다. */
function tabForPath(path) {
  const first = String(path || '/').split('/').filter(Boolean)[0];
  if (!first) return 'home';
  if (first === 'market' || first === 'trade') return 'market';
  if (first === 'news') return 'news';
  if (first === 'ranking') return 'ranking';
  return 'more';
}

/** 로그인 상태는 앱이 갖고, 웹 화면에는 같은 계정을 넘긴다. */
function Shell() {
  const web = useRef(null);
  const signInWeb = useCallback(token => web.current?.signIn(token), []);
  const signOutWeb = useCallback(() => web.current?.signOut(), []);
  return (
    <AuthProvider onWebToken={signInWeb} onWebSignOut={signOutWeb}>
      <AccountProvider>
        <ShellBody web={web} />
      </AccountProvider>
    </AuthProvider>
  );
}

function ShellBody({ web }) {
  const theme = palettes.light;
  const market = useMarket();
  const { user, syncWeb } = useAuth();
  const [tab, setTab] = useState('home');
  // 시장 탭에서 연 종목. null이면 시장 목록을 보여 준다.
  const [stock, setStock] = useState(null);
  // 종목 상세를 열면서 주문 시트도 바로 열지('buy' | 'sell' | null).
  const [stockOrder, setStockOrder] = useState(null);
  // 뉴스 탭에서 연 기사 id. null이면 뉴스 목록. 읽던 기사가 실시간 갱신으로 목록에서 밀려나도 계속 보여 준다.
  const [newsId, setNewsId] = useState(null);
  const lastNews = useRef(null);
  // 로그인 시트. next는 로그인 뒤에 열 화면 주소(없으면 지금 화면에 머문다).
  const [login, setLogin] = useState({ visible: false, next: null });
  const requestLogin = useCallback(next => setLogin({ visible: true, next: next ?? null }), []);
  // 설정 탭에서 메뉴로 연 웹 화면의 첫 주소. null이면 네이티브 메뉴 목록을 보여 준다.
  const [menuRoot, setMenuRoot] = useState(null);
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const menuRootRef = useRef(menuRoot);
  menuRootRef.current = menuRoot;
  const webPath = useRef(PARK_PATH);
  const userRef = useRef(user);
  userRef.current = user;

  const open = useCallback((key, path) => {
    // 시장은 네이티브 화면이다. 주소에 종목 코드가 있으면 상세를 연다.
    if (key === 'market') { setStock(stockFromPath(path)); setStockOrder(null); setTab('market'); return; }
    if (key === 'news') { setNewsId(newsFromPath(path)); setTab('news'); return; }
    const target = path ?? TAB_BY_KEY[key]?.path;
    if (target && target !== webPath.current) {
      web.current?.navigate(target);
      webPath.current = target;
    }
    if (key === 'more') setMenuRoot(target);
    setTab(key);
  }, []);

  const selectTab = key => {
    if (key === 'home') return setTab('home');
    // 시장 탭을 다시 누르면 목록으로 돌아간다.
    if (key === 'market') { if (tab === 'market') setStock(null); return setTab('market'); }
    if (key === 'news') { if (tab === 'news') setNewsId(null); return setTab('news'); }
    if (key === 'ranking') return setTab('ranking');
    // 설정 탭은 누를 때마다 메뉴 목록부터 보여 준다.
    setMenuRoot(null);
    return setTab('more');
  };

  const onRoute = useCallback(path => {
    // 웹이 로그인 화면으로 가려 하면 앱 로그인 시트를 띄운다. 로그인 전용 웹 화면으로 돌아가면
    // 다시 로그인을 요구해 왕복하므로, 웹은 시장으로 옮겨 두고 로그인 뒤에 원래 화면을 연다.
    if (path.startsWith('/login')) {
      const wanted = tabForPath(webPath.current) === 'more' ? webPath.current : null;
      web.current?.navigate(PARK_PATH);
      webPath.current = PARK_PATH;
      if (tabRef.current === 'more') setMenuRoot(null);
      if (!userRef.current) setLogin(current => (current.visible ? current : { visible: true, next: wanted }));
      return;
    }
    webPath.current = path;
    const owner = tabForPath(path);
    const current = tabRef.current;
    // 홈이나 설정 메뉴 목록을 보는 동안 뒤에서 일어난 이동은 탭을 바꾸지 않는다.
    if (['home', 'market', 'news', 'ranking'].includes(current) && owner !== 'home') return;
    if (current === 'more' && !menuRootRef.current) return;
    // 웹 화면(뉴스 등)의 종목 링크는 네이티브 종목 상세로 연다.
    if (owner === 'market') { setStock(stockFromPath(path)); setStockOrder(null); setTab('market'); return; }
    if (owner === 'news') { setNewsId(newsFromPath(path)); setTab('news'); return; }
    if (owner === 'ranking') { setTab('ranking'); return; }
    // 웹 화면의 계정 메뉴에서 계정 화면으로 가면 설정 탭이 그 화면을 이어받는다.
    if (owner === 'more') setMenuRoot(root => root ?? path);
    // 웹에서 로고나 홈 링크를 누르면 네이티브 홈으로 돌아온다.
    setTab(owner);
  }, []);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (tab === 'home') return false;
      if (tab === 'market') {
        if (stock) setStock(null); else setTab('home');
        return true;
      }
      if (tab === 'news') {
        if (newsId) setNewsId(null); else setTab('home');
        return true;
      }
      if (tab === 'more') {
        if (!menuRoot) { setTab('home'); return true; }
        if (webPath.current !== menuRoot && web.current?.goBack()) return true;
        setMenuRoot(null);
        return true;
      }
      setTab('home');
      return true;
    });
    return () => subscription.remove();
  }, [tab, menuRoot, stock, newsId]);

  const showingMenu = tab === 'more' && !menuRoot;
  const found = newsId ? newsWithIds(market.events).find(item => item.id === newsId) ?? null : null;
  if (found) lastNews.current = found;
  const newsItem = found ?? (lastNews.current?.id === newsId ? lastNews.current : null);

  // 로그인 전용 화면은 앱이 먼저 로그인 시트를 띄운다. 로그인되면 원래 가려던 화면을 연다.
  const openMenuItem = useCallback(path => {
    if (!userRef.current) { requestLogin(path); return; }
    open('more', path);
  }, [open, requestLogin]);
  const loginRef = useRef(login);
  loginRef.current = login;
  const closeLogin = useCallback(() => {
    const { next } = loginRef.current;
    setLogin({ visible: false, next: null });
    if (next && userRef.current) open('more', next);
  }, [open]);

  // 앱을 켰을 때 앱만 로그인돼 있으면 웹 화면도 같은 계정으로 맞춘다.
  const webSynced = useRef(false);
  const onWebReady = useCallback(() => {
    if (webSynced.current || !userRef.current) return;
    webSynced.current = true;
    void syncWeb().catch(() => { webSynced.current = false; });
  }, [syncWeb]);
  useEffect(() => {
    if (user) return;
    webSynced.current = false;
    // 로그아웃하면 열려 있던 계정 화면을 닫고 메뉴 목록으로 돌아온다.
    setMenuRoot(null);
  }, [user]);

  return (
    <ThemeProvider value={theme}>
      <StatusBar style="dark" />
      <View style={[styles.root, { backgroundColor: theme.ground }]}>
        <View style={styles.body}>
          <WebPane
            ref={web}
            visible={tab === 'more' && !showingMenu}
            initialPath={PARK_PATH}
            onRoute={onRoute}
            onLoginRequest={() => requestLogin(null)}
            onReady={onWebReady}
          />
          {tab === 'home' ? <HomeScreen market={market} onOpen={open} /> : null}
          {/* 종목 상세를 여는 동안에도 목록을 아래에 그려 둔다. 아이폰에서 상세를 밀어 닫으면 목록이 드러나고 스크롤 위치도 남는다. */}
          {tab === 'market' ? <MarketScreen market={market} onOpenStock={setStock} onLogin={() => requestLogin(null)} /> : null}
          {tab === 'market' && stock ? (
            <SwipeBack key={stock} onBack={() => setStock(null)}>
              <StockScreen code={stock} market={market} initialOrder={stockOrder} onBack={() => setStock(null)} onLogin={() => requestLogin(null)} />
            </SwipeBack>
          ) : null}
          {tab === 'ranking' ? <RankingScreen /> : null}
          {tab === 'news' ? <NewsScreen market={market} onOpenNews={setNewsId} /> : null}
          {tab === 'news' && newsId ? (
            <SwipeBack key={newsId} onBack={() => setNewsId(null)}>
              <NewsDetailScreen
                item={newsItem}
                market={market}
                onBack={() => setNewsId(null)}
                onOpenStock={(code, order) => { setStock(code); setStockOrder(order ?? null); setTab('market'); }}
                onLogin={() => requestLogin(null)}
              />
            </SwipeBack>
          ) : null}
          {showingMenu ? <MenuScreen onOpen={openMenuItem} onLogin={() => requestLogin(null)} /> : null}
        </View>
        <TabBar active={tab} onSelect={selectTab} />
      </View>
      <LoginScreen visible={login.visible} onClose={closeLogin} />
    </ThemeProvider>
  );
}

export default function App() {
  const [fontsLoaded] = useFonts(FONTS);
  return (
    <SafeAreaProvider>
      {fontsLoaded ? <Shell /> : <View style={styles.root} />}
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  body: { flex: 1 },
});
