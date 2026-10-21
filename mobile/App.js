import React, { useCallback, useEffect, useRef, useState } from 'react';
import { BackHandler, StyleSheet, View } from 'react-native';
import { useFonts } from 'expo-font';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { HomeScreen } from './src/screens/HomeScreen';
import { MenuScreen } from './src/screens/MenuScreen';
import { TabBar, TABS } from './src/components/TabBar';
import { WebPane } from './src/WebPane';
import { useMarket } from './src/market';
import { FONTS, ThemeProvider, palettes } from './src/theme';

const TAB_BY_KEY = Object.fromEntries(TABS.map(tab => [tab.key, tab]));

/** 웹 주소가 어느 탭에 속하는지. 홈('/')은 네이티브 홈이고, 계정 화면들은 설정 탭 메뉴에서 연다. */
function tabForPath(path) {
  const first = String(path || '/').split('/').filter(Boolean)[0];
  if (!first) return 'home';
  if (first === 'market' || first === 'trade') return 'market';
  if (first === 'news') return 'news';
  if (first === 'ranking') return 'ranking';
  return 'more';
}

function Shell() {
  const theme = palettes.light;
  const market = useMarket();
  const [tab, setTab] = useState('home');
  // 설정 탭에서 메뉴로 연 웹 화면의 첫 주소. null이면 네이티브 메뉴 목록을 보여 준다.
  const [menuRoot, setMenuRoot] = useState(null);
  const tabRef = useRef(tab);
  tabRef.current = tab;
  const menuRootRef = useRef(menuRoot);
  menuRootRef.current = menuRoot;
  const web = useRef(null);
  const webPath = useRef(TAB_BY_KEY.market.path);

  const open = useCallback((key, path) => {
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
    // 설정 탭은 누를 때마다 메뉴 목록부터 보여 준다.
    if (key === 'more') { setMenuRoot(null); return setTab('more'); }
    // 이미 열린 탭을 다시 누르면 그 탭의 첫 화면으로 돌아간다.
    if (key === tab) return open(key, TAB_BY_KEY[key].path);
    // 그 탭에서 보던 화면이 남아 있으면 그대로, 아니면 탭 첫 화면을 연다.
    return open(key, tabForPath(webPath.current) === key ? webPath.current : TAB_BY_KEY[key].path);
  };

  const onRoute = useCallback(path => {
    webPath.current = path;
    const owner = tabForPath(path);
    const current = tabRef.current;
    // 홈이나 설정 메뉴 목록을 보는 동안 뒤에서 일어난 이동은 탭을 바꾸지 않는다.
    if (current === 'home' && owner !== 'home') return;
    if (current === 'more' && !menuRootRef.current) return;
    // 웹 화면의 계정 메뉴에서 계정 화면으로 가면 설정 탭이 그 화면을 이어받는다.
    if (owner === 'more') setMenuRoot(root => root ?? path);
    // 웹에서 로고나 홈 링크를 누르면 네이티브 홈으로 돌아온다.
    setTab(owner);
  }, []);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      if (tab === 'home') return false;
      if (tab === 'more') {
        if (!menuRoot) { setTab('home'); return true; }
        if (webPath.current !== menuRoot && web.current?.goBack()) return true;
        setMenuRoot(null);
        return true;
      }
      if (webPath.current !== TAB_BY_KEY[tab]?.path && web.current?.goBack()) return true;
      setTab('home');
      return true;
    });
    return () => subscription.remove();
  }, [tab, menuRoot]);

  const showingMenu = tab === 'more' && !menuRoot;

  return (
    <ThemeProvider value={theme}>
      <StatusBar style="dark" />
      <View style={[styles.root, { backgroundColor: theme.ground }]}>
        <View style={styles.body}>
          <WebPane ref={web} visible={tab !== 'home' && !showingMenu} initialPath={TAB_BY_KEY.market.path} onRoute={onRoute} />
          {tab === 'home' ? <HomeScreen market={market} onOpen={open} /> : null}
          {showingMenu ? <MenuScreen onOpen={path => open('more', path)} /> : null}
        </View>
        <TabBar active={tab} onSelect={selectTab} />
      </View>
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
