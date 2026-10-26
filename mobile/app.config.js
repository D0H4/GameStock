const { withAppBuildGradle } = require('expo/config-plugins');

// Android SDK 기본 CMake(3.22.1)의 ninja 1.10은 260자 넘는 경로를 못 다뤄 Windows에서 C++ 빌드가 깨진다.
// 긴 경로를 지원하는 ninja 1.12가 든 CMake를 쓰게 한다. 없으면 Gradle이 SDK에서 받아 온다.
const CMAKE_VERSION = '3.31.6';
const withCmakeVersion = config => withAppBuildGradle(config, gradle => {
  if (!gradle.modResults.contents.includes(`version "${CMAKE_VERSION}"`)) {
    gradle.modResults.contents = gradle.modResults.contents.replace(
      /^android \{\r?\n/m,
      `android {\n    externalNativeBuild { cmake { version "${CMAKE_VERSION}" } }\n`,
    );
  }
  return gradle;
});

// iOS Google 로그인은 iOS 클라이언트 ID를 뒤집은 URL 스킴이 Info.plist에 있어야 돌아올 수 있다.
// ID가 없을 때 플러그인을 넣으면 prebuild가 실패하므로, 값이 있을 때만 넣는다.
module.exports = ({ config }) => {
  const iosClientId = process.env.EXPO_PUBLIC_GOOGLE_IOS_CLIENT_ID;
  const plugins = [...config.plugins];
  if (iosClientId) {
    const iosUrlScheme = `com.googleusercontent.apps.${iosClientId.replace(/\.apps\.googleusercontent\.com$/, '')}`;
    plugins.push(['@react-native-google-signin/google-signin', { iosUrlScheme }]);
  }
  return withCmakeVersion({ ...config, plugins });
};
