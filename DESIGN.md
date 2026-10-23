---
name: 슈엔증권
description: 게임 뉴스와 사람·봇 주문으로 가격이 만들어지는 교육용 모의 게임 주식 거래소 (웹 새 화면, ?ui=next)
colors:
  stage: "#12101c"
  panel: "#1a1726"
  raised: "#241f34"
  line: "#2e2942"
  lane: "#221d31"
  ink: "#f1eef8"
  ink-muted: "#aaa4be"
  ink-faint: "#6f6986"
  brand: "#fd5886"
  brand-text: "#ff7aa2"
  brand-wash: "#3a1d2c"
  up: "#ff4d5e"
  up-text: "#ff6b79"
  up-wash: "#3a1823"
  down: "#4f7cff"
  down-text: "#8aa8ff"
  down-wash: "#1b2445"
  up-fill: "#e0263c"
  down-fill: "#2f5fe0"
  brand-ink: "#12101c"
  guard: "#ffc83d"
  guard-wash: "#3a3014"
  guard-ink: "#241a00"
  news-bg: "#f1eef8"
  news-ink: "#12101c"
  news-up: "#c01f34"
  news-down: "#2f5fe0"
  heat-up-1: "#5a2130"
  heat-up-2: "#8a2537"
  heat-up-3: "#b8283d"
  heat-up-4: "#d8243a"
  heat-down-1: "#23305e"
  heat-down-2: "#2b3f8c"
  heat-down-3: "#3150b8"
  heat-down-4: "#3559cc"
  light-ink-faint: "#a29db0"
  medal-gold: "#f2c94c"
  medal-silver: "#c9ced8"
  medal-bronze: "#d79a63"
  alloc-1: "#c9c3d9"
  alloc-3: "#77708c"
  alloc-4: "#534c68"
  light-alloc-1: "#453f56"
  light-stage: "#efedf5"
  light-panel: "#ffffff"
  light-raised: "#f3f1f8"
  light-line: "#dedae8"
  light-ink: "#1b1726"
  light-ink-muted: "#5c566d"
  light-up-text: "#c01f34"
  light-down-text: "#1f3f9e"
  light-brand-text: "#c8285a"
typography:
  display:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "22px"
    fontWeight: 500
    lineHeight: 1.3
    letterSpacing: "-0.02em"
  price:
    fontFamily: "Pretendard Variable, Pretendard, Malgun Gothic, system-ui, sans-serif"
    fontSize: "44px"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.03em"
    fontFeature: "\"tnum\" 1"
  judge:
    fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif"
    fontSize: "22px"
    fontWeight: 700
    fontFeature: "\"tnum\" 1"
  body:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.45
  number:
    fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 500
    letterSpacing: "-0.01em"
    fontFeature: "\"tnum\" 1"
  label:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "12px"
    fontWeight: 500
  hero:
    fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif"
    fontSize: "64px"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "-0.035em"
    fontFeature: "\"tnum\" 1"
  hero-compact:
    fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif"
    fontSize: "48px"
    fontWeight: 700
  price-compact:
    fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif"
    fontSize: "36px"
    fontWeight: 700
  combo:
    fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif"
    fontSize: "34px"
    fontWeight: 800
    lineHeight: 1
  page-title:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "26px"
    fontWeight: 500
    letterSpacing: "-0.02em"
  change:
    fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif"
    fontSize: "20px"
    fontWeight: 600
  title:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "18px"
    fontWeight: 500
  body-lg:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "16px"
    fontWeight: 500
  body-md:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "15px"
    fontWeight: 500
  number-sm:
    fontFamily: "Pretendard Variable, Pretendard, system-ui, sans-serif"
    fontSize: "13px"
    fontFeature: "\"tnum\" 1"
  caption:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "11px"
    fontWeight: 500
  micro:
    fontFamily: "GyeonggiTitle, Pretendard Variable, Malgun Gothic, sans-serif"
    fontSize: "10px"
    fontWeight: 500
rounded:
  xs: "3px"
  sm: "4px"
  md: "6px"
  lg: "8px"
spacing:
  hairline: "1px"
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "22px"
components:
  panel:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.ink}"
    padding: "16px 18px"
  button-buy:
    backgroundColor: "{colors.up-fill}"
    textColor: "#ffffff"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  button-sell:
    backgroundColor: "{colors.down-fill}"
    textColor: "#ffffff"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.lg}"
    height: "40px"
    padding: "0 14px"
  nav-link-active:
    textColor: "{colors.ink}"
    height: "44px"
    padding: "0 12px"
  watch-row-active:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink}"
    rounded: "{rounded.lg}"
    padding: "6px 8px"
  news-strip:
    backgroundColor: "{colors.news-bg}"
    textColor: "{colors.news-ink}"
    rounded: "{rounded.lg}"
    padding: "12px 16px"
  news-tag:
    backgroundColor: "{colors.brand}"
    textColor: "{colors.brand-ink}"
    rounded: "{rounded.sm}"
    padding: "3px 8px"
  guard-badge:
    backgroundColor: "{colors.guard}"
    textColor: "#241a00"
    rounded: "{rounded.sm}"
    padding: "3px 10px"
  judge-row:
    backgroundColor: "{colors.brand-wash}"
    textColor: "{colors.ink}"
    height: "52px"
---

# Design System: 슈엔증권

## Overview

**Creative North Star: "판정선"**

리듬게임에서 노트가 판정선에 꽂히는 순간처럼, 체결이 현재가라는 줄에 꽂히는 거래소다. 화면 배치는 해외 거래소 표준(종목 목록, 시세·차트, 호가·체결, 잔고, 주문)을 그대로 따르고, 세계관은 색과 숫자의 밀도, 그리고 판정선 하나로만 드러난다. 화려함은 장식이 아니라 데이터에서 나온다. 무대는 보랏빛 남흑색 한 가지이고, 색은 값이 있는 곳에만 켜진다.

제품의 핵심은 "가격에는 이유가 따라온다"이다. 그래서 차트에는 뉴스 시점이 흰 점으로, 차트 아래에는 가장 밝은 면인 뉴스 띠가 붙어 있다. 실제 투자 서비스로 오해받지 않도록 화면 아래에는 늘 모의 시세 안내가 있다.

이 문서는 `?ui=next` 새 화면의 체계다. 기존 화면(흰 종이, 괘선, 2px 모서리)은 옮겨 가는 동안만 남는 이전 체계이며 새 화면의 기준이 아니다.

**Key Characteristics:**
- 다크 기본, 라이트 전환 지원(`html[data-nx-theme]`).
- 낮은 밀도. 패널은 20px(태블릿 14px, 휴대폰 12px) 틈을 두고 떨어져 놓이고, 안쪽 여백은 28px(20px, 16px)이다. 한 화면에 다 넣으려 하지 않는다.
- 숫자는 전부 고정폭 숫자, 글은 경기천년제목.
- 상승 적색, 하락 청색(한국 관행).
- 판정선(핑크)과 체결 노트가 이 체계의 서명이다.

## Colors

무채색 무대 위에 의미가 정해진 색 넷(상승, 하락, 판정, 보호)만 쓴다.

### Primary
- **판정 핑크** (#fd5886): 판정선(현재가 줄 바탕), 키보드 포커스 링, 뉴스 태그. 테두리·밑줄 색으로는 쓰지 않는다. 글자로 쓸 때는 다크에서 #ff7aa2, 라이트에서 #c8285a. 핑크 면 위의 글자는 흰색(3.0:1)이 아니라 무대색 #12101c(6.2:1).

### Secondary
- **상승 적색** (#ff4d5e, 글자 #ff6b79): 상승 등락, 매수 호가, 매수 노트, 매수 버튼.
- **하락 청색** (#4f7cff, 글자 #8aa8ff): 하락 등락, 매도 호가, 매도 노트, 매도 버튼.

### Tertiary
- **보호 노랑** (#ffc83d): VI·서킷브레이커 배지, 목록의 제한 표시, 켜진 관심 별. 그 밖에는 쓰지 않는다.

### Neutral
- **무대** (#12101c): 페이지 바탕, 상단 바, 하단 안내줄.
- **패널** (#1a1726): 모든 작업 구역.
- **돋움** (#241f34): 선택된 행, 눌린 탭, 입력칸.
- **괘선** (#2e2942): 표의 행 구분, 버튼 테두리. 라이트에서는 흰 패널의 경계.
- **잉크** (#f1eef8) / **보조 잉크** (#aaa4be) / **흐린 잉크** (#6f6986, 글자 금지).
- **뉴스 면** (#f1eef8 바탕, #12101c 글자): 다크 무대에서 가장 밝은 면. 라이트에서는 반대로 뒤집힌다.

### Named Rules
**판정색 규칙.** 핑크는 현재가 줄과 키보드 포커스에만 쓴다. 장식이나 강조 박스에 쓰지 않는다.

**무채 선택 규칙.** 현재 탭·선택한 칩·고른 칭호처럼 사람이 고른 상태는 잉크색 밑줄이나 잉크색 테두리와 옅은 바탕(--nx-tint)으로 보인다. 유색 테두리·외곽선은 두르지 않는다.

**보호색 규칙.** 노랑은 거래 보호 상태 하나만 뜻한다. 경고, 공지, 관심 이외의 강조에 빌려 쓰지 않는다(관심 별은 예외로 허용된 유일한 쓰임).

**무채 틀 규칙.** 데이터가 아닌 틀(패널, 바, 버튼 테두리)은 무채색이다. 색이 칠해진 면은 값을 담고 있어야 한다.

## Typography

**Display/Body Font:** 경기천년제목 Medium (Pretendard Variable 대체)
**Number Font:** Pretendard Variable, `tnum` 고정폭 숫자

**Character:** 제목 서체 하나로 글을 쓰고, 숫자만 폭이 고정된 산세리프로 바꿔 호가 열이 흔들리지 않게 한다. 배포본이 한 굵기뿐이라 위계는 크기와 색으로 만든다.

### Hierarchy
- **Price** (700, 44px, 1): 종목 머리의 현재가. 휴대폰에서 36px.
- **Display** (500, 22px, 1.3): 종목 이름.
- **Judge** (700, 22px): 판정선 위 현재가.
- **Body** (500, 14~15px, 1.45): 뉴스 제목, 메뉴.
- **Number** (500, 12~14px, tnum): 표, 호가, 잔량, 지수.
- **Label** (500, 12~13px): 패널 제목, 통계 이름. 보조 잉크색.
- **Hero** (700, 64px, 휴대폰 48px, tnum): 홈의 게임주 지수. 카드 없이 무대 위에 놓는다.
- **Page title** (500, 26px): 시장·뉴스·랭킹 같은 화면 제목.
- **Combo** (800, 34px): 체결 하이웨이의 연속 체결 수.
- **Change** (600, 20px) / **Title** (18px): 지수 변화, 시장 지도의 등락률.
- **Caption / Micro** (11px / 10px): 레인 이름, 보호 표시 같은 아주 작은 표식. 본문에는 쓰지 않는다.

크기 단계는 10 · 11 · 12 · 13 · 14 · 15 · 16 · 18 · 20 · 22 · 26 · 34 · 36 · 44 · 48 · 64px 만 쓴다.

### Named Rules
**고정폭 숫자 규칙.** 가격·수량·비율은 `.nx-num`(또는 `.num`)을 달아 Pretendard tnum으로 쓴다. 경기천년제목으로 숫자 열을 만들지 않는다.

## Layout

거래 화면은 UnrealPFT의 `StockPage`와 `TicketDock`에서 배치만 참고한다. 색상, 가격 표기와 거래 기능은 슈엔증권의 것을 유지한다. 데스크톱은 최대 1280px 안에 시세·차트 가변 열과 336px 주문 열을 두며, 주문창은 스크롤 중에도 따라온다. 주식 상세의 종목 전환은 상단 선택 상자로, 종목 정보·잔고·호가·관련 뉴스는 차트 아래로 배치한다. 1150px 이하에서는 주문 열을 308px로 줄인다. 960px 이하에서는 본문을 한 열로 바꾸고 하단 주문 버튼으로 네이티브 모달 주문창을 연다. 주문 입력은 하나만 유지해 창을 닫거나 화면 폭이 바뀌어도 보존한다. ETF는 주식 메뉴의 `/market/etf` 목록에서 검색·상승/하락 필터·열 정렬로 탐색하며, 종목을 누르면 `/market/etf/:ticker`의 개별 차트·주문 화면을 연다. ETF 선택에는 드롭다운을 쓰지 않으며 상세에서 목록으로 돌아갈 수 있다. ETF에 없는 거래량을 만들지 않고 구성 종목 수·기준가·최근 가격 흐름을 표시한다. `/derivatives`는 선물·옵션 전용이고, 이전 `/derivatives/etf` 주소는 ETF의 새 주소로 이동한다.

## Elevation & Depth

그림자를 쓰지 않는다. 깊이는 무대(#12101c) < 패널(#1a1726) < 돋움(#241f34)의 세 단계 명도로만 표현한다. 가장 밝은 면은 뉴스 띠다.

### Named Rules
**평평한 무대 규칙.** 그림자, 유리, 그라데이션 장식 없음. 위로 뜨는 것은 명도로만 뜬다.

## Shapes

모서리는 8px(패널 속 버튼, 행, 뉴스 띠, 게임 아이콘), 6px(차트 버튼, 레인, 작은 아이콘), 4px(배지, 태그, 코드, 시장 지도 칸), 3px(노트, 잔량 막대, 범위 막대)만 쓴다. 패널과 입력칸은 10px(--nx-radius)이다. 테두리는 1px 하나.

## Components

### Buttons
- **Shape:** 8px.
- **매수/매도:** 상승 적색 / 하락 청색 면에 흰 글자. 이 둘만 색이 칠해진 버튼이다. 다크에서는 흰 글자 대비(4.5:1)를 위해 한 단계 진한 면(#e0263c / #2f5fe0)을 쓴다.
- **Ghost:** 투명 바탕, 1px 괘선 테두리, 보조 잉크 글자. 높이 40px. 호버 시 잉크색과 돋움 바탕.
- **Focus:** 2px 핑크 외곽선, 2px 띄움.

### Navigation
- 상단 바 56px, 무대색. 링크는 높이 44px, 보조 잉크. 현재 탭은 잉크색과 아래 2px 잉크 밑줄.
- 메뉴 오른쪽에 게임주 지수, 그 다음 테마 전환(해·달 아이콘, 테두리 없음)이 로그인·계정 버튼 바로 왼쪽에 붙는다.
- 본문이 지수를 크게 보이는 화면(홈, 로그인, 파생)에서는 상단 바 지수를 비워 둔다. 본문이 로그인 화면·로그인 안내인 곳에서는 상단 바 로그인 버튼을 비워 둔다. 자리는 남겨 버튼 위치가 움직이지 않는다.

### Named Rules
**한 화면 한 번 규칙.** 같은 값·이름·버튼을 한 화면에 두 번 보이지 않는다. 상단 메뉴가 화면 이름을 보이면 본문 제목은 화면 낭독기용(.vh)으로만 두고, 상세 화면은 탭 대신 "← 목록" 링크 하나로 돌아간다. 차트 머리에는 종목 코드·통화를 다시 쓰지 않는다.

**한 줄 높이 규칙.** 한 줄에 함께 놓이는 버튼·세그먼트·칩·검색·선택 상자·체크·글자 버튼은 모두 44px이다. 줄마다 따로 그리는 목록의 숫자 칸은 폭을 고정해 위아래 끝을 맞춘다.

### Cards / Containers
- 패널은 둥근 면이고 무대와는 명도 차이와 넓은 틈으로 갈린다(라이트는 1px 괘선). 카드 속 카드는 만들지 않는다.

### Inputs / Fields
- 돋움 바탕, 괘선 테두리, 잉크 글자.

### 체결 하이웨이 (Signature Component)
호가 사다리 왼쪽 112px 레인. 매수(왼쪽, 적)·매도(오른쪽, 청) 두 줄. 최근 체결 최대 9건이 아래에서 위로 쌓이고, 노트 높이는 수량의 제곱근에 비례(6~28px)하며, 오래될수록 옅어진다. 새 체결은 위에서 떨어져(420ms, ease-out) 판정선에 꽂힌다. 판정선 아래에는 같은 방향 연속 체결 수(콤보)와 마지막 체결 시각을 보인다. 움직임 줄이기에서는 정지 화면.

### 판정선
현재가 줄. 선 없이 brand-wash 바탕으로만 표시하고, 현재가는 22px 굵은 고정폭 숫자. 레인까지 가로지른다.

### 가격 차트
주식 상세는 최근 1시간의 1분봉으로 시작한다. TradingView의 봉 간격·가격축·십자선 배치를 참고하되 상승 적색·하락 청색은 유지한다. 캔들은 화면 픽셀 기준 1px 꼬리와 채운 몸통으로 그리고, 오른쪽 가격축·하단 시간축·현재가 점선을 둔다. 상단 시가·고가·저가·종가는 선택한 봉의 값이다. 진행 중인 봉은 실제 서버 체결 시각으로 종가·고가·저가를 즉시 갱신하고, 다음 구간의 첫 체결에서 새 봉을 만든다. 저장 기록은 서버의 마지막 갱신 시각으로 대조해 늦게 온 응답이 최신 봉을 되돌리지 않게 한다. 마우스·터치·키보드로 기록을 탐색하고, 십자선 이동 시 봉 전체를 다시 그리지 않는다. 뉴스 차트는 선형과 뉴스 시점 표시를 유지한다. 거래 수량이 아닌 기록 수를 거래량 막대로 표시하지 않는다.

### 시장 지도 (홈)
15종목을 칸으로 나눈 지도. 칸 면적은 시가총액, 색 농도는 등락 크기(4%마다 한 단계, 4단계). 보합은 돋움색. 보호 상태 종목은 노란 2px 안쪽 테두리와 표식.

### 지수 흐름 (홈)
지수 기록 API가 없어 종목별 가격 기록으로 서버 식(기준가 대비 비율의 시가총액 가중 평균 × 1000)을 10분 간격으로 다시 계산한다. 기준 1,000은 점선. 마지막 점은 실시간 지수.

### 랭킹 순위 원판
1~3위는 이모지 메달 대신 순위 숫자를 금(#f2c94c)·은(#c9ced8)·동(#d79a63) 원판 위에 진한 잉크로 쓴다. 4위부터는 보조 잉크 숫자만.

### 자산 구성 막대 (내 계좌)
현금은 핑크, 종목은 잉크에서 무대 쪽으로 내려가는 무채색 명도 다섯 단계. 색 수를 늘리지 않고 비중만 읽히게 한다.

### 담보비율 게이지 (대출)
100%~300% 트랙 위에 반대매매 구간(상승 바탕색)과 경고 구간(보호 바탕색)을 깔고, 지금의 비율은 핑크 판정선 하나로 꽂는다.

### 감싼 화면(관리자 구획, 로그인 카드)
동작과 구조는 기존 그대로 두고 공통 부품만 바꾼다: 패널은 8px 면, 패널 제목은 13px 보조 잉크, 요약 숫자는 48px 고정폭, 표는 1px 괘선과 돋움색 호버, 주 버튼은 잉크 면(색 면은 매수·매도만), 입력은 돋움 바탕과 보조 잉크 포커스 테두리.

### 뉴스 띠
뉴스 태그(핑크) + 제목 + 출처·시각 + "발행 후" 등락률. 서버 값이 없는 영향 점수는 만들어 보여 주지 않는다.

## 앱(mobile/)에 옮길 때 달라지는 점

앱은 같은 토큰을 `mobile/src/theme.js`에 옮겨 쓴다. 구성은 국내 증권앱 방식(PRODUCT.md)이고, 아래만 웹과 다르다(감사 006, 2026-10-21).

- **아이콘:** Ionicons. Expo에 들어 있어 따로 설치하지 않고, 같은 그림의 채운 모양과 외곽선 모양이 짝으로 있다. 그래서 현재 탭은 채운 모양, 나머지는 외곽선으로 상태를 모양으로도 구분한다. 메뉴 아이콘은 그 화면이 다루는 대상(지갑, 선물, 돈, 리본, 방패)으로 고른다.
- **오류·위험 표시:** 적색은 상승만 뜻하므로 입력 오류와 되돌릴 수 없는 동작에는 쓰지 않는다. 잉크색 글자와 테두리에 경고 아이콘을 달고, 위험 동작은 확인 창을 한 번 더 거친다.
- **자산 구성 막대:** 그래픽 대비 3:1을 지키려고 종목 칸 다섯 단계를 다크 `#F1EEF8 #CFC9DF #ADA6C2 #918AA8 #7A7391`, 라이트 `#1B1726 #3A3449 #554F66 #6F6986 #857F99`로 쓴다. 위 Colors의 alloc 값보다 끝 단계가 밝다(다크) 또는 진하다(라이트).
- **담보비율 게이지:** 반대매매·경고 구간은 연한 바탕색이 트랙과 구분되지 않아(1.0~1.2:1) 진한 면(다크 `#FF6B79`·`#FFC83D`, 라이트 `#C01F34`·`#9A6D00`)을 쓴다. 판정선은 트랙 위아래로 튀어나오고 카드색 테두리를 두른다.
- **라이트 테두리(edge):** 바탕 `#EFEDF5` 위에서도 3:1이 되도록 `#87828F`를 쓴다.
- **흐림 없음:** 로그인 창 뒤는 흐리지 않고 어둡게만 덮는다.

## Do's and Don'ts

### Do:
- **Do** 가격 옆에 이유를 둔다: 차트의 흰 뉴스 점, 차트 아래 뉴스 띠.
- **Do** 숫자는 전부 tnum 고정폭 숫자로 쓴다.
- **Do** 움직임은 값이 바뀔 때만: 체결 노트, 가격 플래시. 움직임 줄이기 설정을 따른다.
- **Do** 새 화면은 `html[data-nx]` 범위 안에서만 토큰을 바꾼다. 기존 화면을 건드리지 않는다.

### Don't:
- **Don't** 서버에 없는 값(영향 점수, 사람·봇 구분 등)을 지어서 보여 주지 않는다.
- **Don't** 핑크·노랑을 장식이나 일반 강조에 쓰지 않는다.
- **Don't** 그림자, 유리 효과, 그라데이션 글자, 카드 속 카드를 쓰지 않는다.
- **Don't** 노트처럼 움직이는 요소를 하이웨이 밖으로 퍼뜨리지 않는다.
- **Don't** 실제 투자 서비스처럼 보이게 하지 않는다. 모의 시세 안내를 늘 남긴다.
