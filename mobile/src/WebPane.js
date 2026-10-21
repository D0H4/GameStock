import React, { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { Linking, StyleSheet, View } from 'react-native';
import * as AuthSession from 'expo-auth-session';
import * as Crypto from 'expo-crypto';
import * as Notifications from 'expo-notifications';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { WebView } from 'react-native-webview';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { API_BASE_URL, WEB_APP_URL } from './config';

const MOBILE_AUTH_STATE_KEY = 'gamestock-mobile-auth-state';
const PRICE_ALERT_PERMISSION_KEY = 'gamestock-price-alert-permission-asked';

const withShell = path => `${path}${path.includes('?') ? '&' : '?'}app-shell=1`;

/*
 * 웹 문서가 그려지기 전에 실행한다.
 * 1) 네이티브 탭바가 있으니 웹 상단의 화면 이동 메뉴 줄만 숨긴다(로고·계정 메뉴는 남긴다).
 * 2) 웹 라우터의 주소 변경을 앱에 알려, 앱이 현재 탭을 맞추고 홈('/')은 네이티브 홈으로 돌린다.
 */
const BEFORE_LOAD = `(function () {
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
 * 홈을 뺀 탭이 공유하는 WebView 하나. 앱 시작과 함께 뒤에서 불러 두어 로그인 상태를 받고,
 * 탭을 바꿀 때는 새로 불러오지 않고 웹 라우터로 주소만 옮긴다.
 */
export const WebPane = forwardRef(function WebPane({ visible, initialPath, onRoute }, ref) {
  const insets = useSafeAreaInsets();
  const webViewRef = useRef(null);
  const ready = useRef(false);
  const pendingAuth = useRef(null);
  // 첫 로딩이 끝나기 전의 주입은 무시되므로, 마지막 이동 요청을 들고 있다가 로딩이 끝나면 보낸다.
  const pendingPath = useRef(null);
  const pendingAuthState = useRef(null);
  const canGoBack = useRef(false);
  const redirectUri = AuthSession.makeRedirectUri({ scheme: 'gamestock', path: 'auth/callback' });
  const startUrl = `${WEB_APP_URL}${withShell(initialPath)}`;

  const inject = script => webViewRef.current?.injectJavaScript(`${script}; true;`);
  const postWebAuth = payload => {
    if (!ready.current) { pendingAuth.current = payload; return; }
    inject(`window.dispatchEvent(new CustomEvent('gamestock-native-auth',{detail:${JSON.stringify(payload)}}))`);
  };

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
  }), []);

  const startGoogleLogin = async next => {
    const state = Crypto.randomUUID();
    pendingAuthState.current = state;
    try {
      await AsyncStorage.setItem(MOBILE_AUTH_STATE_KEY, state);
      const authUrl = new URL(startUrl);
      authUrl.pathname = '/login/google';
      authUrl.searchParams.set('next', typeof next === 'string' ? next : '/');
      authUrl.searchParams.delete('app-shell');
      authUrl.searchParams.set('mobileAuth', '1');
      authUrl.searchParams.set('returnUri', redirectUri);
      authUrl.searchParams.set('state', state);
      await Linking.openURL(authUrl.toString());
    } catch (error) {
      pendingAuthState.current = null;
      await AsyncStorage.removeItem(MOBILE_AUTH_STATE_KEY).catch(() => {});
      postWebAuth({ type: 'GOOGLE_AUTH_ERROR', message: error?.message || 'Google 로그인을 완료하지 못했습니다.' });
    }
  };

  const handleMessage = event => {
    let message;
    try { message = JSON.parse(event.nativeEvent.data); } catch { return; }
    if (message?.type === 'ROUTE') { onRoute?.(message.path); return; }
    // 마이페이지에서 가격 알림을 처음 저장했을 때만 네이티브 알림 권한을 묻는다.
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
      return;
    }
    if (message?.type === 'GOOGLE_LOGIN') void startGoogleLogin(message.next);
  };

  useEffect(() => {
    const processDeepLink = async url => {
      if (!url) return;
      try {
        const callback = new URL(url);
        if (!['gamestock:', 'exp:'].includes(callback.protocol)) return;
        const expectedState = pendingAuthState.current || await AsyncStorage.getItem(MOBILE_AUTH_STATE_KEY);
        if (!expectedState) return;
        if (callback.searchParams.get('state') !== expectedState) throw new Error('인증 상태가 일치하지 않습니다.');
        const code = callback.searchParams.get('code');
        if (!code) throw new Error('로그인 인증 코드를 받지 못했습니다.');
        const exchange = await fetch(`${API_BASE_URL}/api/auth/mobile/exchange`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ code }),
        });
        const payload = await exchange.json();
        if (!exchange.ok || !payload.customToken) throw new Error(payload.message || '로그인 인증 교환에 실패했습니다.');
        pendingAuthState.current = null;
        await AsyncStorage.removeItem(MOBILE_AUTH_STATE_KEY).catch(() => {});
        postWebAuth({ type: 'FIREBASE_CUSTOM_TOKEN', customToken: payload.customToken });
      } catch (error) {
        pendingAuthState.current = null;
        await AsyncStorage.removeItem(MOBILE_AUTH_STATE_KEY).catch(() => {});
        postWebAuth({ type: 'GOOGLE_AUTH_ERROR', message: error?.message || 'Google 로그인을 완료하지 못했습니다.' });
      }
    };
    const subscription = Linking.addEventListener('url', ({ url }) => void processDeepLink(url));
    Linking.getInitialURL().then(processDeepLink).catch(() => {});
    return () => subscription.remove();
  }, []);

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
          if (pendingAuth.current) {
            const payload = pendingAuth.current;
            pendingAuth.current = null;
            postWebAuth(payload);
          }
        }}
        onMessage={handleMessage}
      />
    </View>
  );
});

// 숨긴 동안에도 WebView를 살려 두어야 로그인 상태와 웹 화면의 위치가 유지된다.
const styles = StyleSheet.create({ hidden: { opacity: 0, zIndex: -1 } });
