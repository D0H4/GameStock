# 모바일 앱 실행 및 로그인 설정

```powershell
cd mobile
npm install
# 배포 백엔드를 사용할 때는 위 환경변수 생략 가능
$env:EXPO_PUBLIC_API_BASE_URL = "https://gamestock-api.161-33-33-45.sslip.io"
npx expo start
```

앱은 `WebMirrorScreen`으로 웹 공용 화면을 표시합니다. Firebase Console의 Google 및 이메일/비밀번호 공급자 설정은 웹과 같습니다.
로컬 화면을 사용할 때는 `EXPO_PUBLIC_WEB_APP_URL`을 PC의 LAN 주소로 지정하고, 해당 웹의 `VITE_API_BASE_URL`도 휴대폰에서 접근 가능한 PC 주소를 사용합니다.
앱의 Google 인증 코드 교환에 쓰는 `EXPO_PUBLIC_API_BASE_URL` 역시 같은 백엔드를 가리켜야 합니다.

로그인이 필요한 기능을 누르면 `/login`에서 방법을 선택합니다. 이메일 로그인·회원가입·비밀번호 찾기는 공용 웹 화면에서 처리합니다.
이메일과 비밀번호는 앱이나 서비스 DB에 별도로 저장하지 않으며 Firebase SDK가 인증합니다.

## Google 로그인

Google을 선택하면 앱을 떠나지 않고 로그인합니다(`@react-native-google-signin/google-signin`).

- **Android**: Google Play 서비스의 계정 선택 창이 앱 위에 바로 뜹니다.
- **iOS**: `"슈엔증권"이(가) 로그인하기 위해 "google.com"을(를) 사용하려고 합니다` 시스템 팝업이 뜨고, **계속**을 누르면 Google 로그인 화면이 열립니다.

받은 Google ID 토큰으로 Firebase에 로그인한 뒤 `/api/auth/login`으로 거래 계정을 연결합니다. 네이티브 모듈이 들어가므로 Expo Go가 아닌 개발 빌드(`npx expo run:android`, `npx expo run:ios` 또는 EAS 빌드)로 실행해야 합니다.

```powershell
# Firebase 콘솔 > Authentication > Google > 웹 SDK 구성의 웹 클라이언트 ID
$env:EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = "<번호>-<문자열>.apps.googleusercontent.com"
# Google Cloud 콘솔에서 번들 ID com.gamestock.mobile로 만든 iOS 클라이언트 ID
$env:EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID = "<번호>-<문자열>.apps.googleusercontent.com"
```

- Android는 ID를 넣지 않습니다. 같은 Google Cloud 프로젝트에 패키지명 `com.gamestock.mobile`과 빌드 서명 키의 SHA-1로 Android 클라이언트를 등록해 둬야 합니다. 디버그 키와 릴리스(EAS) 키를 둘 다 등록하세요. 등록이 빠지면 계정 선택 후 `DEVELOPER_ERROR`가 납니다.
- `app.config.js`는 `EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID`가 있을 때만 iOS URL 스킴(`com.googleusercontent.apps.…`)을 Info.plist에 넣습니다. ID를 바꾸면 iOS를 다시 빌드해야 합니다.

클라이언트 ID가 비어 있거나 Expo Go에서 실행하면 예전 웹 방식으로 돌아갑니다. 이때는 앱 안 인증 세션(iOS는 같은 시스템 팝업)으로 `/login/google`을 열고, **앱 로그인 완료하기**를 누르면 `gamestock://auth/callback`으로 자동으로 돌아옵니다.
백엔드는 Firebase ID 토큰을 2분 동안 한 번만 쓸 수 있는 코드로 교환하고, 앱은 그 코드를 Custom Token으로 바꿉니다.

어느 방식이든 로그인한 뒤에는 앱이 Custom Token을 WebView에 넘겨 웹 화면도 같은 계정으로 로그인됩니다. 실물 기기에서 전체 인증 흐름을 따로 확인해야 합니다.
