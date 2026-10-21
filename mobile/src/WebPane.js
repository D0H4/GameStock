import React, { forwardRef, useImperativeHandle, useRef } from 'react';
import { StyleSheet, View } from 'react-native';
import * as Notifications from 'expo-notifications';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { WebView } from 'react-native-webview';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WEB_APP_URL } from './config';

const PRICE_ALERT_PERMISSION_KEY = 'gamestock-price-alert-permission-asked';
const SIGN_OUT_FLAG = 'gamestock-native-signout';

const withShell = path => `${path}${path.includes('?') ? '&' : '?'}app-shell=1`;

/*
 * 웹 문서가 그려지기 전에 실행한다.
 * 1) 앱이 로그아웃을 요청했으면, 웹 Firebase가 로그인 정보를 읽기 전에 그 저장소를 지운다.
 *    IndexedDB 요청은 순서대로 처리되므로 지우기가 Firebase의 열기보다 먼저 끝난다.
 * 2) 네이티브 탭바가 있으니 웹 상단의 화면 이동 메뉴 줄만 숨긴다(로고·계정 메뉴는 남긴다).
 * 3) 웹 라우터의 주소 변경을 앱에 알려, 앱이 현재 탭을 맞추고 홈과 로그인은 네이티브 화면으로 돌린다.
 */
const BEFORE_LOAD = `(function () {
  try {
    if (localStorage.getItem('${SIGN_OUT_FLAG}')) {
      localStorage.removeItem('${SIGN_OUT_FLAG}');
      indexedDB.deleteDatabase('firebaseLocalStorageDb');
    }
  } catch (error) {}
  var style = document.createElement('style');
  style.textContent = '.topbar .nav { display: none !important; }';
  (document.head || document.documentElement).appendChild(style);
  function report() {
    if (window.ReactNativeWebView) window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'ROUTE', path: location.pathname }));
  }
  ['pushState', 'replaceState'].forEach(function (name) {
    var original = history[name];
    history[name] = function () { var result = original.apply(this, arguments); report(); return result; };
  });
  window.addEventListener('popstate', report);
})(); true;`;

/**
 * 아직 네이티브로 옮기지 않은 화면이 공유하는 WebView 하나. 앱 시작과 함께 뒤에서 불러 두고,
 * 탭을 바꿀 때는 새로 불러오지 않고 웹 라우터로 주소만 옮긴다. 로그인은 앱이 하고 웹에 넘긴다.
 */
export const WebPane = forwardRef(function WebPane({ visible, initialPath, onRoute, onLoginRequest, onReady }, ref) {
  const insets = useSafeAreaInsets();
  const webViewRef = useRef(null);
  const ready = useRef(false);
  const pendingToken = useRef(null);
  // 첫 로딩이 끝나기 전의 주입은 무시되므로, 마지막 이동 요청을 들고 있다가 로딩이 끝나면 보낸다.
  const pendingPath = useRef(null);
  const canGoBack = useRef(false);
  const startUrl = `${WEB_APP_URL}${withShell(initialPath)}`;

  const inject = script => webViewRef.current?.injectJavaScript(`${script}; true;`);
  const sendToken = token => inject(`window.dispatchEvent(new CustomEvent('gamestock-native-auth',{detail:${JSON.stringify({ type: 'FIREBASE_CUSTOM_TOKEN', customToken: token })}}))`);
  const navigateNow = path => {
    const target = JSON.stringify(withShell(path));
    inject(`if (location.pathname + location.search !== ${target}) { history.pushState(null, '', ${target}); window.dispatchEvent(new PopStateEvent('popstate')); window.scrollTo(0, 0); }`);
  };

  useImperativeHandle(ref, () => ({
    navigate(path) {
      if (!ready.current) { pendingPath.current = path; return; }
      navigateNow(path);
    },
    goBack() {
      if (!canGoBack.current) return false;
      webViewRef.current?.goBack();
      return true;
    },
    /** 앱에서 로그인한 계정을 웹 화면에도 로그인시킨다(웹 AuthBridge가 커스텀 토큰으로 로그인한다). */
    signIn(token) {
      if (!ready.current) { pendingToken.current = token; return; }
      sendToken(token);
    },
    /** 웹 화면도 로그아웃시킨다. 다음 로딩 때 BEFORE_LOAD가 웹의 로그인 저장소를 지운다. */
    signOut() {
      pendingToken.current = null;
      inject(`try { localStorage.setItem('${SIGN_OUT_FLAG}', '1'); } catch (error) {} location.reload()`);
    },
  }), []);

  const handleMessage = event => {
    let message;
    try { message = JSON.parse(event.nativeEvent.data); } catch { return; }
    if (message?.type === 'ROUTE') { onRoute?.(message.path); return; }
    // 웹 로그인 화면의 Google 버튼은 앱 로그인 시트로 넘긴다.
    if (message?.type === 'GOOGLE_LOGIN') { onLoginRequest?.(); return; }
    // 가격 알림을 처음 저장했을 때만 네이티브 알림 권한을 묻는다.
    if (message?.type === 'PRICE_ALERT_ADDED') {
      void (async () => {
        try {
          if (await AsyncStorage.getItem(PRICE_ALERT_PERMISSION_KEY) === '1') return;
          await AsyncStorage.setItem(PRICE_ALERT_PERMISSION_KEY, '1');
          await Notifications.requestPermissionsAsync();
        } catch {
          // 권한 확인 실패는 알림 저장 자체에 영향을 주지 않는다.
        }
      })();
    }
  };

  return (
    <View
      style={[StyleSheet.absoluteFill, { paddingTop: insets.top, backgroundColor: '#fff' }, !visible && styles.hidden]}
      pointerEvents={visible ? 'auto' : 'none'}
      importantForAccessibility={visible ? 'auto' : 'no-hide-descendants'}
      accessibilityElementsHidden={!visible}
    >
      <WebView
        ref={webViewRef}
        source={{ uri: startUrl }}
        style={{ flex: 1 }}
        startInLoadingState
        javaScriptEnabled
        domStorageEnabled
        cacheEnabled={false}
        cacheMode="LOAD_NO_CACHE"
        sharedCookiesEnabled
        thirdPartyCookiesEnabled
        injectedJavaScriptBeforeContentLoaded={BEFORE_LOAD}
        onNavigationStateChange={state => { canGoBack.current = state.canGoBack; }}
        onLoadStart={() => { ready.current = false; }}
        onLoadEnd={() => {
          ready.current = true;
          if (pendingPath.current) {
            const path = pendingPath.current;
            pendingPath.current = null;
            navigateNow(path);
          }
          if (pendingToken.current) {
            const token = pendingToken.current;
            pendingToken.current = null;
            sendToken(token);
          }
          onReady?.();
        }}
        onMessage={handleMessage}
      />
    </View>
  );
});

// 숨긴 동안에도 WebView를 살려 두어야 웹 화면의 위치가 유지된다.
const styles = StyleSheet.create({ hidden: { opacity: 0, zIndex: -1 } });
