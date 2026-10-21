import React, { createContext, useContext } from 'react';
import { Text } from 'react-native';

// 글꼴은 웹과 같은 경기천년제목 하나만 쓴다. 배포본이 Medium 한 굵기뿐이라
// fontWeight를 주지 않고, 위계는 크기와 색으로 만든다.
export const FONT_FAMILY = 'GyeonggiTitle';
export const FONTS = { [FONT_FAMILY]: require('../assets/fonts/GyeonggiTitleM.ttf') };

// 텍스트 색은 카드 바탕 대비 4.5:1 이상인 값만 쓴다.
// 상승 적·하락 청은 한국 관행, 상호작용 색은 브랜드 핑크 하나다.
const light = {
  scheme: 'light',
  ground: '#F2F3F5',
  card: '#FFFFFF',
  pressed: '#F4F4F7',
  ink: '#1A1620',
  inkSoft: '#5F5A66',
  line: '#ECEBEF',
  // 지수 등락은 카드 없이 바탕 위에 놓이므로 바탕(#F2F3F5) 대비로도 4.5:1을 넘긴다(4.79:1).
  up: '#D0222B',
  upWash: '#FDECEE',
  down: '#2F5BD8',
  downWash: '#EAF0FD',
  flat: '#5F5A66',
  brand: '#D83060',
  brandWash: '#FEF0F4',
  onBrand: '#FFFFFF',
  warn: '#8A5A00',
  warnWash: '#FFF4DB',
  // 기준가 점선은 읽어야 하는 정보라 카드 대비 3:1 이상(3.41:1)으로 둔다.
  chartBase: '#8E8995',
};

// 웹이 라이트 테마 하나로 설계돼 있어, 탭을 오갈 때 밝기가 튀지 않도록 앱도 라이트만 쓴다.
export const palettes = { light };

const ThemeContext = createContext(light);
export const ThemeProvider = ThemeContext.Provider;
export const useTheme = () => useContext(ThemeContext);

// 글자 크기 단계. 픽셀 값을 화면마다 따로 적지 않는다.
export const TYPE = {
  display: { fontSize: 34, lineHeight: 40 },
  title: { fontSize: 22, lineHeight: 28 },
  headline: { fontSize: 18, lineHeight: 24 },
  body: { fontSize: 16, lineHeight: 22 },
  callout: { fontSize: 15, lineHeight: 20 },
  caption: { fontSize: 13, lineHeight: 18 },
  micro: { fontSize: 12, lineHeight: 16 },
};

export const SPACE = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 };
export const RADIUS = 16;

/** 모든 텍스트는 이 컴포넌트로 그린다. 시스템 글자 크기 설정은 1.4배까지 따른다. */
export function Txt({ variant = 'body', tone = 'ink', style, numeric, ...props }) {
  const theme = useTheme();
  return (
    <Text
      maxFontSizeMultiplier={1.4}
      {...props}
      style={[
        { fontFamily: FONT_FAMILY, color: theme[tone] ?? tone },
        TYPE[variant],
        numeric && { fontVariant: ['tabular-nums'] },
        style,
      ]}
    />
  );
}

export const toneOf = value => (value > 0 ? 'up' : value < 0 ? 'down' : 'flat');
