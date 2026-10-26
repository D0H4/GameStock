// 앱은 웹과 동일한 OCI 백엔드를 기본으로 사용한다.
// 로컬 Spring Boot를 테스트할 때만 EXPO_PUBLIC_API_BASE_URL에
// PC의 LAN 주소를 지정해 덮어쓴다.
export const API_BASE_URL =
  process.env.EXPO_PUBLIC_API_BASE_URL || "https://gamestock-api.161-33-33-45.sslip.io";
export const MARKET_SOCKET_URL =
  API_BASE_URL.replace(/^http/, "ws") + "/ws/market";

// 앱 안 웹 화면, Google 로그인, 공지 그림, OpenAPI 문서는 서비스 도메인(Firebase Hosting과 같은 배포)을 쓴다.
// 로컬 웹 화면을 확인할 때는 EXPO_PUBLIC_WEB_APP_URL에 PC의 LAN 주소를 지정한다.
export const WEB_APP_URL =
  process.env.EXPO_PUBLIC_WEB_APP_URL || "https://syuenasset.xyz";

// 웹 Firebase 설정과 동일한 프로젝트를 사용한다.
export const FIREBASE_CONFIG = {
  apiKey: "AIzaSyAzUEj5xwH14p3BBj7RwJ92tj9eoM9cuKI",
  authDomain: "gamestock-20994.firebaseapp.com",
  projectId: "gamestock-20994",
  storageBucket: "gamestock-20994.firebasestorage.app",
  messagingSenderId: "957680390567",
  appId: "1:957680390567:web:8f0d60ea888daae68e2682",
};
// 예전 네이티브 인증 화면과의 호환성을 위해 남겨둔 값이다.
// Google 로그인은 외부 브라우저와 딥링크, 이메일 로그인은 공통 웹 화면을 사용한다.
export const GOOGLE_WEB_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID || "";
export const GOOGLE_IOS_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID || "";
export const GOOGLE_ANDROID_CLIENT_ID = process.env.EXPO_PUBLIC_GOOGLE_ANDROID_CLIENT_ID || "";
