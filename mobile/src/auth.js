import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Linking } from 'react-native';
import * as AuthSession from 'expo-auth-session';
import * as Crypto from 'expo-crypto';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { initializeApp } from 'firebase/app';
import {
  createUserWithEmailAndPassword, getAuth, getReactNativePersistence, initializeAuth, onIdTokenChanged,
  sendPasswordResetEmail, signInWithCustomToken, signInWithEmailAndPassword, signOut as firebaseSignOut, validatePassword,
} from 'firebase/auth';
import { API_BASE_URL, FIREBASE_CONFIG, WEB_APP_URL } from './config';

const firebaseApp = initializeApp(FIREBASE_CONFIG);
let firebaseAuth;
try { firebaseAuth = initializeAuth(firebaseApp, { persistence: getReactNativePersistence(AsyncStorage) }); }
catch { firebaseAuth = getAuth(firebaseApp); }

const MOBILE_AUTH_STATE_KEY = 'gamestock-mobile-auth-state';

export class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

/** 서버 요청. 로그인 상태면 Firebase ID 토큰을 붙인다. 서버의 오류 문구가 있으면 그대로 돌려준다. */
export async function request(path, { method = 'GET', body, auth = true } = {}) {
  const user = firebaseAuth.currentUser;
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && user) headers.Authorization = `Bearer ${await user.getIdToken()}`;
  const response = await fetch(`${API_BASE_URL}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!response.ok) throw new ApiError((data && data.message) || '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.', response.status);
  return data;
}

/** 웹 lib/auth.ts authErrorMessage와 같은 문구. */
export function authErrorMessage(error) {
  const code = error && typeof error === 'object' && 'code' in error ? String(error.code) : '';
  switch (code) {
    case 'auth/invalid-email': return '이메일 주소를 확인해 주세요.';
    case 'auth/invalid-credential':
    case 'auth/user-not-found':
    case 'auth/wrong-password': return '이메일 또는 비밀번호를 확인해 주세요.';
    case 'auth/email-already-in-use': return '이미 사용 중인 이메일이에요. 로그인하거나 비밀번호를 재설정해 주세요.';
    case 'auth/weak-password':
    case 'auth/password-does-not-meet-requirements': return '비밀번호가 가입 조건에 맞지 않아요. 더 길고 복잡한 비밀번호를 입력해 주세요.';
    case 'auth/operation-not-allowed': return '이 로그인 방법은 지금 쓸 수 없어요. 다른 방법을 선택해 주세요.';
    case 'auth/too-many-requests': return '요청이 많아 잠시 제한됐어요. 잠시 후 다시 시도해 주세요.';
    case 'auth/user-disabled': return '이 계정은 지금 로그인할 수 없어요.';
    case 'auth/network-request-failed': return '연결을 확인한 뒤 다시 시도해 주세요.';
    default: return error?.message && !code ? error.message : '로그인을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.';
  }
}

const AuthContext = createContext(null);
export const useAuth = () => useContext(AuthContext);

/**
 * 앱 로그인 상태. 로그인하면 웹과 같이 /api/auth/login으로 거래 계정을 연결하고,
 * WebView로 여는 웹 화면도 같은 계정이 되도록 커스텀 토큰을 onWebToken으로 넘긴다.
 */
export function AuthProvider({ children, onWebToken, onWebSignOut }) {
  const [user, setUser] = useState(firebaseAuth.currentUser);
  const [ready, setReady] = useState(false);
  const [profile, setProfile] = useState(null);
  const [googleError, setGoogleError] = useState('');
  const pendingState = useRef(null);
  const redirectUri = AuthSession.makeRedirectUri({ scheme: 'gamestock', path: 'auth/callback' });
  const webTokenRef = useRef(onWebToken);
  webTokenRef.current = onWebToken;

  const loadProfile = useCallback(async () => {
    try { setProfile(await request('/api/profile')); } catch { /* 프로필은 화면마다 다시 읽는다. */ }
  }, []);

  useEffect(() => onIdTokenChanged(firebaseAuth, next => {
    setUser(next);
    setReady(true);
    if (next) void loadProfile(); else setProfile(null);
  }), [loadProfile]);

  /** WebView의 웹 화면을 앱과 같은 계정으로 로그인시킨다. 서버가 일회용 코드를 커스텀 토큰으로 바꿔 준다. */
  const syncWeb = useCallback(async customToken => {
    if (!firebaseAuth.currentUser) return;
    let token = customToken;
    if (!token) {
      const { code } = await request('/api/auth/mobile/issue', { method: 'POST' });
      ({ customToken: token } = await request('/api/auth/mobile/exchange', { method: 'POST', body: { code }, auth: false }));
    }
    if (token) webTokenRef.current?.(token);
  }, []);

  /** 서버 거래 계정 연결(웹과 같은 /api/auth/login)과 웹 화면 로그인. */
  const connect = useCallback(async customToken => {
    await request('/api/auth/login', { method: 'POST' });
    await syncWeb(customToken).catch(() => {});
    await loadProfile();
  }, [loadProfile, syncWeb]);

  const signInEmail = useCallback(async (email, password) => {
    await signInWithEmailAndPassword(firebaseAuth, email.trim(), password);
    await connect();
  }, [connect]);

  const register = useCallback(async (email, password) => {
    const validation = await validatePassword(firebaseAuth, password);
    if (!validation.isValid) {
      const missing = [
        validation.meetsMinPasswordLength === false ? `${validation.passwordPolicy.customStrengthOptions.minPasswordLength}자 이상` : '',
        validation.containsLowercaseLetter === false ? '영문 소문자' : '',
        validation.containsUppercaseLetter === false ? '영문 대문자' : '',
        validation.containsNumericCharacter === false ? '숫자' : '',
        validation.containsNonAlphanumericCharacter === false ? '특수문자' : '',
      ].filter(Boolean);
      throw new Error(missing.length ? `비밀번호 조건을 확인해 주세요: ${missing.join(', ')}` : '비밀번호가 가입 조건에 맞지 않아요.');
    }
    await createUserWithEmailAndPassword(firebaseAuth, email.trim(), password);
    await connect();
  }, [connect]);

  const resetPassword = useCallback(email => sendPasswordResetEmail(firebaseAuth, email.trim()), []);

  /** Google은 기본 브라우저의 웹 /login/google에서 인증하고, 딥링크로 받은 일회용 코드를 커스텀 토큰으로 바꾼다. */
  const startGoogle = useCallback(async () => {
    setGoogleError('');
    const state = Crypto.randomUUID();
    pendingState.current = state;
    await AsyncStorage.setItem(MOBILE_AUTH_STATE_KEY, state);
    const url = new URL('/login/google', WEB_APP_URL);
    url.searchParams.set('next', '/');
    url.searchParams.set('mobileAuth', '1');
    url.searchParams.set('returnUri', redirectUri);
    url.searchParams.set('state', state);
    await Linking.openURL(url.toString());
  }, [redirectUri]);

  useEffect(() => {
    const finish = async url => {
      if (!url) return;
      let callback;
      try { callback = new URL(url); } catch { return; }
      if (!['gamestock:', 'exp:'].includes(callback.protocol) || !callback.searchParams.get('code')) return;
      const expected = pendingState.current || await AsyncStorage.getItem(MOBILE_AUTH_STATE_KEY);
      if (!expected) return;
      pendingState.current = null;
      await AsyncStorage.removeItem(MOBILE_AUTH_STATE_KEY).catch(() => {});
      try {
        if (callback.searchParams.get('state') !== expected) throw new Error('인증 상태가 일치하지 않아요. 다시 로그인해 주세요.');
        const { customToken } = await request('/api/auth/mobile/exchange', { method: 'POST', body: { code: callback.searchParams.get('code') }, auth: false });
        await signInWithCustomToken(firebaseAuth, customToken);
        await connect(customToken);
      } catch (error) {
        setGoogleError(authErrorMessage(error));
      }
    };
    const subscription = Linking.addEventListener('url', ({ url }) => void finish(url));
    Linking.getInitialURL().then(finish).catch(() => {});
    return () => subscription.remove();
  }, [connect]);

  const signOut = useCallback(async () => {
    await firebaseSignOut(firebaseAuth);
    onWebSignOut?.();
  }, [onWebSignOut]);

  const value = useMemo(() => ({
    user, ready, profile, googleError, signInEmail, register, resetPassword, startGoogle, signOut, syncWeb, refreshProfile: loadProfile,
  }), [user, ready, profile, googleError, signInEmail, register, resetPassword, startGoogle, signOut, syncWeb, loadProfile]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
