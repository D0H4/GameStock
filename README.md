# 슈엔증권 (GameStock)

게임을 가상 주식 종목으로 거래하며 뉴스, 호가, 체결, 가격 변화를 관찰하는 교육용 시장 시뮬레이션 프로젝트다. 서비스 화면의 이름은 **슈엔증권**, 저장소와 백엔드 프로젝트 이름은 **GameStock**이다.

사용자는 모의 자금으로 게임 종목을 사고팔고, 보유 자산과 투자 성과를 확인한다. 서버에서는 사용자와 봇의 주문을 매칭하고, RSS 뉴스 분석과 시장 시뮬레이션을 함께 실행한다. 실제 기업의 주식이나 현금을 거래하는 서비스는 아니다.

> 문서 기준: **2026-10-21, 현재 로컬 소스 코드와 저장소 설정**. 아래의 ‘구현’은 코드가 연결돼 있다는 뜻이며, 운영 배포 상태나 실제 기기에서의 통합 검증 완료를 뜻하지 않는다.

## 현재 진행 상황

웹·백엔드·DB·Firebase 인증을 연결한 핵심 거래 기능이 구현돼 있다. 최근에는 뉴스 상세 화면, 주간 수익률 랭킹, 칭호, 모의 대출, 관리자 기능까지 범위가 확장됐다. 모바일은 홈·시장·종목·뉴스·랭킹·로그인을 네이티브 화면으로 제공하고, 설정 메뉴에서 여는 계정 화면은 WebView로 웹 클라이언트를 이어서 사용한다. 현재 단계는 기능을 추가한 상태에서 거래 정합성과 전체 이용 흐름을 검증·보완하는 단계다.

| 영역            | 현재 구현 범위                                                                      | 확인이 더 필요한 부분                                             |
| --------------- | ----------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| 시장 조회       | 15개 종목, 검색·정렬·필터, 차트, 호가, 최근 체결, 상·하한가 표시                    | 운영 중 전 종목의 호가·체결 지속성                                |
| 주문·계좌       | 지정가·시장가, 부분 체결, 미체결 취소, 예약금, 수수료, 정산, 보유·손익·거래 내역    | 인증된 주문부터 정산까지 통합 검증, 동시 주문·재시작 복구         |
| 시장 시뮬레이션 | 전략별 참여자 봇, 유동성 공급, 접속 상태에 따른 작업 조절                           | 실제 사용자가 원하는 수량을 체결할 수 있는 유동성과 장시간 안정성 |
| 거래 보호       | 가격제한폭, VI, 시장 서킷브레이커, 단일가 처리                                      | 발동·주문 제한·해제까지 이어지는 시나리오 검증                    |
| 뉴스            | RSS 수집, 관련성 필터·중복 처리, 규칙 기반 영향 분석, 목록·상세·기사 발췌·원문 링크 | 분석 정확도, 수집·본문 추출 실패 처리 품질                        |
| 인증·프로필     | Google·이메일 로그인, 회원가입·비밀번호 재설정, 닉네임·프로필 사진, 계좌 리셋       | 최신 웹·앱 로그인 및 계정 복구 흐름                               |
| 참여 기능       | 관심종목, 출석, 일일 미션, 전체·주간 랭킹, 칭호 획득·장착·프로모션 코드             | 페이지 방문 미션의 실제 수행 확인, 주간 경계 동작                 |
| 모의 대출       | 대출·상환·원장, 일일 이자, 담보 경고·반대매매                                       | 시세 급변·체결 부족 상황의 반대매매 통합 검증                     |
| 관리자          | 사용자 검색, 거래 제한·해제, 강제 닉네임 변경, 관리자 권한 검사                     | 운영 계정 권한·조치 이력 검증                                     |
| 모바일          | Expo 네이티브 탭·로그인·시장·뉴스·랭킹 화면, 설정 메뉴의 계정 화면 WebView 연동      | 실제 기기 로그인·거래·딥링크, 서명 APK 릴리스                     |
| 배포            | Firebase Hosting 웹 배포와 OCI 백엔드 배포를 GitHub Actions로 연결                   | 현재 활성 커밋과 운영 동작은 별도 확인 필요                       |

가격 변동 이유를 제공하는 `price-drivers` API는 있지만 공통 화면에 연결되지 않았다. 가격 알림·댓글·태그도 API와 저장 구조가 있으며, 알림 발동과 사용자 화면은 완성 범위에서 제외한다. 시즌별 자산 초기화나 투자 성적 공유 카드도 현재 기능으로 안내하지 않는다.

## 주요 화면

| 경로                              | 내용                                                                   |
| --------------------------------- | ---------------------------------------------------------------------- |
| `/`                               | 게임주 지수·내 자산, 많이 오른 게임, 거래량 상위 종목, 속보, 보유 종목 |
| `/market`, `/market/:code`        | 종목 목록과 상세 차트·호가·체결·주문                                   |
| `/trade`, `/trade/:code`          | 종목 선택, 호가, 주문 티켓, 보유·체결·미체결 계좌 패널                 |
| `/news`, `/news/:id`              | 뉴스 목록과 선택 기사 상세, 기사 발췌·원문 링크·발행 이후 가격 변화    |
| `/ranking`                        | 순자산 기준 전체 랭킹과 이번 주 수익률 랭킹, 프로필·장착 칭호          |
| `/mypage`                         | 내 계좌, 보유·손익, 주문·체결·정산 내역                                |
| `/settings`                       | 닉네임·프로필 사진 설정, 계좌 리셋                                     |
| `/charge`                         | 오늘의 투자 미션과 모의 자금 보상                                      |
| `/loan`                           | 모의 대출·상환, 한도·담보비율·이자·원장                                |
| `/titles`                         | 칭호 조건·획득 현황·장착                                               |
| `/admin`                          | 관리자 전용 사용자 조회와 거래 제한·닉네임 조치                        |
| `/login`                          | 로그인 방법 선택                                                       |
| `/login/google`, `/login/email`   | Google·이메일 로그인                                                   |
| `/login/register`, `/login/reset` | 회원가입·비밀번호 재설정                                               |

내 계좌·설정·충전·대출·칭호·관리자 화면은 로그인이 필요하다. 관리자 API는 서버에서도 ADMIN 권한을 확인한다. 화면은 라이트 테마 하나만 쓰고(다크 모드 없음), 모바일 화면을 지원한다.

### 상장 종목

기본 카탈로그는 프론트의 `src/market/universe.ts`와 백엔드의 `StockCatalog.java`에 있다.

| 코드 | 게임                  | 코드 | 게임                  |
| ---- | --------------------- | ---- | --------------------- |
| UMA  | 우마무스메 프리티더비 | BA   | 블루 아카이브         |
| GOV  | 승리의 여신: 니케     | ZZZ  | 젠레스 존 제로        |
| GI   | 원신                  | SR   | 붕괴: 스타레일        |
| AK   | 명일방주: 엔드필드    | WH   | 명조: 워더링 웨이브   |
| CRK  | 쿠키런: 킹덤          | VR   | 발로란트              |
| ES   | 에픽세븐              | KRP  | 카트라이더 러쉬플러스 |
| OW   | 오버워치              | PW   | 팰월드                |
| TR   | 트릭컬 리바이브       |      |                       |

시작 시 누락 종목을 추가하고 일부 구 종목 코드·이름을 이전하는 로직이 있다. 운영 DB의 종목 구성은 기본 카탈로그와 별도로 확인해야 한다.

## 거래·성과 계산 기준

- 신규 일반 계정의 기본 모의 자금은 **100만원**이다.
- 사람과 봇 주문은 같은 호가·매칭 체계를 사용한다. 지정가 주문은 가격·시간 우선으로 처리하고, 체결 결과는 DB 커밋을 거쳐 응답한다.
- 사용자가 참여하는 체결에는 매수·매도 각각 **0.1% 수수료**를 적용한다. 봇끼리의 체결은 반복 거래로 시뮬레이션 자금이 소진되지 않도록 수수료를 면제한다. 예약 현금과 미정산 금액을 구분해 계좌를 계산한다.
- 일일 가격제한폭은 기준가 대비 **±30%**다. 봇의 일일 가격 범위와 시장가 체결 범위에는 별도 제한이 있다. VI와 시장 서킷브레이커는 서버의 거래 보호 상태로 관리한다.
- 기본 참여자 봇 수는 **6,020명**이며 환경변수로 바꿀 수 있다. 설정된 체결 목표는 실제 체결량이나 유동성을 보장하지 않는다.
- 전체 랭킹은 **현금 + 예약 현금 + 미정산 매도대금 + 보유 평가액 − 대출 잔액**을 기준으로 정렬한다. 봇·데모·관리자 계정은 제외하고 상위 100명을 반환한다. 전체 수익률에서는 출석·미션 보상도 제외한다.
- 주간 랭킹은 한국시간 월요일을 기준으로 주간 시작 자산과 수익률을 계산한다. 월요일 0시 주간 칭호 지급 로직이 있으며, 시즌 종료 후 전 계좌를 초기화하는 기능과는 별개다.
- 일일 미션은 매일 3개를 선정하고 건당 **5만원**을 지급한다. 관심종목 미션은 서버가 등록 여부를 확인하지만, 페이지 확인형 미션은 보상 요청만으로 실제 방문을 충분히 입증하지 못한다.
- 대출 한도는 순자산의 **50%**, 최소 대출액은 **1만원**, 일일 이자는 **0.1%**다. 담보비율 140% 미만이면 경고·신규 대출 제한, 120% 미만이면 미체결 취소와 시장가 매도를 통한 반대매매를 시도한다. 체결 가능 여부는 시장 상태에 영향을 받는다.

## 데이터 흐름과 기술 구성

### 봇 시장 균형 정책

- 모든 종목이 같은 주문 판단 주기와 체결 목표를 사용한다. 대형주의 변동성·가치 회귀·가격 범위는 유지하지만 주문 간격 배수와 체결 목표 감속은 적용하지 않는다. 네 봇 그룹은 그룹당 하나의 요청만 대기시키고 도착 순서대로 실행하며, 중복 틱은 같은 실행 결과를 공유한다. 봇 DB 기록은 한 번에 하나씩 처리하고 사람 주문 접수 경로는 별도로 유지한다. `bot.pendingGroups`, `bot.coalesced`, `bot.queue.wait`, `bot.batch.elapsed` 지표로 대기와 실행 시간을 구분한다.
- 배치당 주문 한도 안에서 실제 보충 가능한 호가가 다음 10주 주문으로 얇아질 때만 LP 보충 여유를 확보한다. 보충이 필요하지 않거나 공급할 자원이 없으면 그 여유를 일반 참여자 주문에 사용한다.

- 참여 봇의 신규 주문은 기본 5~10주, 유효시간은 최대 30초다. 실제 현금·보유 한도로 5주를 살 수 없으면 신규 매수를 내지 않으며, 5주 미만의 기존 잔여 재고 매도·초과 재고 정리와 부분 체결은 허용한다. 배치 경로는 봇별·종목별 주문을 하나 유지하고, 기존 중복·10주 초과 주문은 해당 봇의 판단 차례에 취소·재평가한다. 사용자 주문에는 이 제한을 적용하지 않는다.
- 추가 참여 봇은 가치·스윙 60주, 단기 자금 수요 계열 20주, 나머지 40주의 보유 한도를 갖는다. 빠른 판단 주기와 전략별 보유기간을 분리하고, 목표 보유량 초과분에 재고 부담을 적용한다. 단기 자금 수요의 목표 수량도 주문 단위에 맞추되 현금으로 살 수 있는 범위를 유지한다. 이익이 나도 매수 판단이 유지되면 일괄 익절하지 않는다.
- 소형주의 추세 신호는 개별 가치 추정과 함께 평가한다. 단기 자금 수요 계열은 전체 종목에서 공통 가치평가가 매매 방향을 덮지 않도록 개인별 목표 보유량을 유지한다. 소형주 단기 주문의 가치평가 영향은 최근 체결 기준가 주변으로 제한한다. 보유기간이 지난 초과 재고는 매도 호가가 4주 이하일 때 체결 기준가 주변의 작은 지정가 주문으로 처분한다. 시장 상태·개별 추정은 자정에 초기화하지 않으며 현재가는 실제 체결로만 바뀐다. 상승 마감을 강제하지 않는다.
- LP는 양방향 최대 4단계, 단계당 최대 32주를 공급하고 재고 과잉 매도와 부족분 재매입을 모두 수행한다. 배치 매칭은 각 LP 호가의 마지막 1주를 사람 주문용으로 남긴다. 봇 매도 흡수 시 같은 종목의 매수 예약 현금도 포함해 예비금을 검사하며, 내부 거래의 수수료 면제를 반영한다. 재고가 4주 이하이고 현재가의 2% 이내에 다른 매도 물량이 4주 미만일 때 최대 32주를 보충한다. 보충 후 전체 보유 주식은 `max(512, 종목별 초기 LP 재고 × 4)` 이내로 제한하며, 기존 보유량이 이를 초과한 경우 강제로 회수하지 않는다.
- LP의 단계별 목표 수량은 최소 12주로 잡되, 실제 재고·현금·위험 한도를 넘겨 공급하지 않는다. 봇 배치 시작과 종료 시 한쪽 상위 5단계 수량이 4주 이하이고 공급 자원이 있으면 정기 갱신 시간을 기다리지 않고 다시 호가를 낸다. 따라서 배치 종료 전에 소진된 물량도 보충하며, 두 차례 처리에서 신규 주문 최대 400건이라는 기존 한도를 유지한다. 사람 주문으로 소진되면 다음 실행 가능한 봇 배치에서 검사한다. 거래 정지·자원 부족·DB 지연 중에도 빈 호가가 절대 발생하지 않는다는 보장은 아니다.
- 소형주 방향성 봇은 당일 변동이 ±15%를 넘으면 ±20% 봇 안전 경계에 가까워질수록 추격 성향을 줄인다. 공통의 오래된 내재가치 때문에 상단 매수·하단 매도가 일제히 쌓이지 않도록 하며, 개인 재고를 순환시키는 단기 자금 수요 계열의 판단에는 이 공통 보정을 적용하지 않는다.
- `mvn "-Dtest=BotLiquidityRecoveryTest" test`는 15개 종목 각각에 대해 LP 재고가 없고 기존 유통량이 보충 한도 이상이며 내재가치가 현재가의 0.5배 또는 2배인 30가지 상태를 검사한다. 추가 발행 없이 실제 매칭·LP 재매입·양방향 호가·후반 체결·현금과 주식 수 보전을 확인하는 메모리 시뮬레이션이며, 운영 DB나 실제 배포 검증은 별도다.
- `backend/`에서 `mvn "-Dtest=BotMarketBalanceTest" test`로 실제 주문 매칭·현금 보전·호가 적체·가격 변화·한국시간 자정 이후 거래를 검사한다. 기본 검사는 3일에 걸쳐 하루 10분씩 거래하며 중간 유휴시간의 거래를 소급 생성하지 않는다. 72시간 연속 운영이나 실제 배포 검증을 의미하지 않는다. `-Dbot.balance.sessionMillis=21600000`으로 일별 6시간, `-Dbot.balance.symbols=TR,ES`로 검사 종목을 지정할 수 있다.

```text
React 웹 ── Firebase SDK ── Google / 이메일 인증
   │ Firebase ID 토큰
   ├─ REST /api/* ──────────────┐
   └─ WebSocket /ws/stream ─────┤
                               ▼
                    Spring Boot + JDBC + MySQL
                    ├─ 계좌·주문·정산·대출·칭호
                    ├─ 메모리 호가·매칭 → DB 배치 저장
                    └─ RSS 수집·분석·봇·거래 보호

Expo 앱 네이티브 화면 ─ REST /api/* · WebSocket /ws/stream ─┘
   └ 설정 메뉴의 계정 화면 일부 → WebView → 같은 React 웹
```

| 구분      | 사용 기술                                                     |
| --------- | ------------------------------------------------------------- |
| 웹        | React 19, TypeScript, Vite 7, Firebase Web SDK, CSS           |
| 서버      | Java 17, Spring Boot 3.5, JDBC, WebSocket, Firebase Admin SDK |
| DB        | MySQL, 시작 시 보완 스키마·초기 데이터 처리                   |
| 모바일    | Expo 57, React Native 0.86, WebView(설정 메뉴의 계정 화면), Expo Auth Session |
| 배포 설정 | Firebase Hosting, GitHub Actions, OCI(Nginx·systemd), Railway 참고 설정       |

시세·뉴스·시장 상태를 REST로 가져온 뒤 WebSocket을 구독한다. 스트림이 연결되지 않으면 2초 간격 REST 갱신을 사용하고, 연결 중에도 30초마다 REST로 보정한다. 로그인한 요청과 스트림에는 Firebase ID 토큰을 사용한다.

첫 화면에는 저장된 캐시나 초기 모의 스냅샷이 사용될 수 있다. 초기 표시 데이터는 운영 시장의 최신 상태를 증명하지 않으며, 브라우저에서 독립적인 오프라인 거래 시장을 계속 실행하지 않는다.

**API 연결 주의:** `src/lib/api.ts`는 `VITE_API_BASE_URL` 하나를 명시적으로 사용한다. 개발 환경에서 값이 없으면 `http://localhost:8081`, 프로덕션 빌드에서 값이 없으면 OCI API 주소를 기본값으로 사용하며, 다른 DB로 자동 재시도하지 않는다. 주문·계좌 변경을 시험하기 전 현재 환경변수와 실제 연결 대상을 확인해야 한다.

뉴스 분석은 가중 키워드·관련성 규칙을 사용한다. 생성형 AI나 학습된 주가 예측 모델로 설명하지 않는다. 뉴스 이후의 가격 변화율은 다른 거래·시장 요인의 영향도 포함하므로 해당 기사만의 인과 효과를 뜻하지 않는다.

## 로컬 실행

### 준비 사항

- Node.js **20.19 이상 또는 22.12 이상**과 npm(Vite 7 실행 조건)
- Java 17, Maven, MySQL 8 계열
- Firebase 프로젝트와 Google·이메일/비밀번호 로그인 설정
- 백엔드에서 ID 토큰을 검증할 Firebase Admin 서비스 계정

### 1. MySQL 초기화

새 개발 DB에서 MySQL 클라이언트로 실행한다.

```sql
SOURCE C:/path/to/GameStock/database/schema.sql;
```

`schema.sql`은 DB와 기본 테이블을 만드는 초기화 파일이다. 기존 DB에 반복 적용하는 마이그레이션 명령으로 사용하지 않는다. 추가 테이블·컬럼·기본 종목은 백엔드 시작 시 보완한다.

### 2. 백엔드

PowerShell에서 환경변수를 지정한다. 서비스 계정 파일 경로는 실제 절대 경로로 바꾼다.

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3306/gamestock?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME = '개발_DB_사용자'
$env:DB_PASSWORD = '개발_DB_비밀번호'
$env:FIREBASE_SERVICE_ACCOUNT_PATH = 'C:\path\to\firebase-admin.json'
$env:GAMESTOCK_CORS_ORIGIN = 'http://localhost:5180'

Set-Location backend
mvn spring-boot:run
```

기본 API 주소는 `http://localhost:8081`, 상태 확인 경로는 `/api/health`다. DB 세션은 UTC를 사용하며, 출석·미션·주간 집계 등 서비스 날짜는 한국시간 기준으로 처리한다.

루트의 `.env`를 읽어 실행하는 `scripts/run-backend.ps1`도 있다. 다만 이 스크립트는 **MySQL80 서비스와 특정 로컬 Java·IntelliJ Maven 설치 경로**에 의존한다. 다른 PC에서는 위의 표준 Maven 실행 방법을 쓰거나 자신의 경로에 맞게 스크립트를 조정해야 한다. Spring Boot를 직접 실행하면 루트 `.env`를 자동으로 읽지 않는다.

### 3. 웹

별도 터미널에서 저장소 루트로 이동해 실행한다.

```powershell
Copy-Item .env.example .env.local
npm ci
npm run dev
```

`.env.local`의 `VITE_API_BASE_URL`을 `http://localhost:8081`로 맞추고, `VITE_FIREBASE_*`를 사용할 Firebase 프로젝트 설정으로 바꾼다. 기본 웹 주소는 **http://localhost:5180**이다.

```powershell
npm run typecheck
npm run build
npm run preview
```

빌드 결과는 `dist/`에 생성된다. `npm start`는 `server/index.js`로 빌드된 정적 파일을 제공하며 Spring Boot API를 실행하지 않는다.

### 4. Expo 앱

```powershell
Set-Location mobile
npm ci
# 로컬 백엔드를 사용할 때만 지정한다. 운영 기본값은 OCI API다.
$env:EXPO_PUBLIC_API_BASE_URL = 'http://PC의_LAN_IP:8081'
# 설정 메뉴에서 여는 계정 화면(WebView)을 로컬 웹으로 확인할 때만 지정한다.
$env:EXPO_PUBLIC_WEB_APP_URL = 'http://PC의_LAN_IP:5180'
npm start
```

휴대폰에서 로컬 웹에 접근하려면 웹 개발 서버를 `npm run dev -- --host 0.0.0.0`으로 실행하고, 웹의 API 주소도 휴대폰에서 접근할 수 있는 PC의 LAN 주소로 지정한다. 백엔드 CORS에도 해당 웹 출처를 설정한다.

현재 `mobile/App.js`는 네이티브 탭 셸을 진입점으로 사용한다. 홈·시장 목록·종목 상세·주문 시트·뉴스 목록·뉴스 상세·랭킹·로그인(이메일·Google·회원가입·비밀번호 재설정)은 네이티브 화면이다. 설정 메뉴의 내 계좌·충전·대출·칭호·프로필/계정 설정만 `mobile/src/WebPane.js`의 WebView로 웹 클라이언트를 연다. `EXPO_PUBLIC_API_BASE_URL`은 네이티브 REST·WebSocket과 앱 인증에 사용하고, `EXPO_PUBLIC_WEB_APP_URL`은 WebView가 열 웹 주소를 정한다. Google 로그인은 외부 브라우저와 `gamestock://auth/callback` 딥링크를 사용한다.

## 환경변수와 배포 설정

| 위치                           | 주요 설정                                                                                                                                                                                          |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 웹 `.env.local` 또는 빌드 환경 | `VITE_API_BASE_URL`, `VITE_FIREBASE_API_KEY`, `VITE_FIREBASE_AUTH_DOMAIN`, `VITE_FIREBASE_PROJECT_ID`, `VITE_FIREBASE_STORAGE_BUCKET`, `VITE_FIREBASE_MESSAGING_SENDER_ID`, `VITE_FIREBASE_APP_ID` |
| 백엔드                         | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `GAMESTOCK_CORS_ORIGIN`, 선택적 `PORT`                                                                                                                     |
| Firebase Admin                 | `FIREBASE_SERVICE_ACCOUNT_PATH` 또는 `FIREBASE_SERVICE_ACCOUNT_JSON`(JSON 문자열·파일 경로 지원)                                                                                                   |
| 관리자 지정                    | `GAMESTOCK_ADMIN_GOOGLE_UID`, `GAMESTOCK_ADMIN_GOOGLE_EMAIL`                                                                                                                                       |
| 시뮬레이션                     | `GAMESTOCK_BOT_PARTICIPANTS`, `GAMESTOCK_MATCHING_ENABLED`, `GAMESTOCK_BATCH_BOTS_ENABLED` 등. 전체 기본값은 `application.yml` 참고                                                                |
| 모바일                         | `EXPO_PUBLIC_API_BASE_URL`(네이티브 REST·WebSocket·인증), `EXPO_PUBLIC_WEB_APP_URL`(설정 메뉴 WebView 주소)                                                                                         |
| GitHub Actions                 | `FIREBASE_SERVICE_ACCOUNT` 저장소 시크릿                                                                                                                                                           |

서비스 계정 JSON, DB 비밀번호, Firebase ID 토큰은 Git에 넣지 않는다. `VITE_*`는 브라우저에 포함되는 공개 설정이므로 관리자 자격증명을 넣으면 안 된다.

운영 웹은 Firebase Hosting, 백엔드와 DB는 OCI를 사용한다.

- 웹: https://gamestock-20994.web.app
- API: https://gamestock-api.161-33-33-45.sslip.io

Firebase Hosting은 `firebase.json`에 따라 `dist/`를 제공하고 SPA 경로를 `index.html`로 연결한다. `.github/workflows/firebase-hosting.yml`은 `main` push 또는 수동 실행 시 Node 24 환경에서 의존성 설치·타입 검사·빌드 후 배포한다. `.github/workflows/oci-backend.yml`은 백엔드 변경 시 Java 17로 테스트·패키징하고 OCI에 배포한 뒤 HTTPS API와 WebSocket을 확인한다. 웹·앱 기본 API 주소는 OCI를 가리키며, 한 화면에서 다른 DB로 자동 전환하지 않는다.

OCI에서는 Oracle Linux의 `gamestock.service`가 Java 17 백엔드를 실행하고 Nginx가 HTTPS/WSS를 제공한다. MySQL은 사설망에서 연결하며 자격증명은 `/etc/gamestock/production.env`에만 둔다. IP를 포함한 임시 도메인이므로 공인 IP가 바뀌면 주소와 인증서도 갱신해야 한다. 운영·백업·복구 절차는 [OCI 운영 문서](docs/oci-operations.md)를 따른다.

기존 Railway 설정은 현재 운영 경로가 아니라 롤백·구성 참고용으로 보존한다. OCI와 Railway의 매칭 엔진을 동시에 실행하지 않는다.

| 서비스 루트 | 참고 설정 파일         | 빌드·실행                                                                                              |
| ----------- | ---------------------- | ------------------------------------------------------------------------------------------------------ |
| 저장소 루트 | `railway.json`         | `cd backend && mvn -DskipTests clean package` → `java -jar backend/target/gamestock-backend-0.1.0.jar` |
| `backend/`  | `backend/railway.json` | `mvn -DskipTests clean package` → `java -jar target/gamestock-backend-0.1.0.jar`                       |

헬스체크는 `/api/health`, 서버 포트는 `PORT`가 우선이다. 두 Railway 빌드 설정은 테스트를 생략하므로 배포 성공과 테스트 통과는 별도로 확인한다. 웹과 백엔드도 각각 배포된다.

## 저장소 구조

```text
src/
  pages/                 홈·시장·거래·뉴스·계좌·대출·칭호·관리자 화면
  components/            차트·호가·주문·뉴스 상세·계좌 등 공통 UI
  market/                종목·타입·계좌 계산·REST/스트림 연동·초기 모의 엔진
  lib/                   Firebase·인증·API·프로필·칭호 공통 처리
  styles/                웹 CSS·테마 스타일
  router.ts              브라우저 history 기반 라우팅
backend/
  src/main/java/         인증·관리자·시장·주문·봇·뉴스·대출·칭호·WebSocket
  src/main/resources/    application.yml
  src/test/              거래·동시성·뉴스·보호장치·인증·대출·칭호 등 테스트
  ops/                   OCI 배포·검증 스크립트와 Railway 참고 SQL
mobile/
  App.js                네이티브 탭 셸·로그인·WebView 연결
  src/screens/          홈·시장·종목·뉴스·랭킹·설정·로그인 화면
  src/WebPane.js        설정 메뉴의 계정 화면용 웹 클라이언트
  src/auth.js, market.js 네이티브 인증·REST·WebSocket 시장 연동
database/               MySQL 초기 스키마
scripts/                 로컬 실행·시장 API·DB 정합성 점검
server/
  index.js              `dist/`를 제공하는 Node 정적 서버(`npm start`)
  src/                  현재 실행 기준이 아닌 기존 데모 서버 코드
public/                 웹 브랜드·게임 아이콘·캐릭터 자산
.github/workflows/      Firebase Hosting·OCI 배포 워크플로
docs/                    주제별 설계·검증·설정 문서
```

상세 문서는 과거 설계·검증 계획도 포함한다. 현재 동작은 해당 소스와 실행 결과를 기준으로 확인한다.

- [프로젝트 범위](docs/project-scope.md)
- [가격 형성 모델](docs/price-formation-model.md)
- [실시간 구조](docs/realtime-architecture.md)
- [뉴스 분석 검증](docs/news-analysis-validation.md)
- [거래 엔진 검증](docs/market-engine-validation.md)
- [Firebase 로그인 설정](docs/firebase-login-setup.md)
- [모바일 설정](docs/mobile-app-setup.md)
- [모바일 네이티브 전환 현황](docs/mobile-app-next-steps.md)
- [배포 구조](docs/deployment-structure.md)

## 검증 방법과 다음 작업

웹 정적 검사는 저장소 루트에서 `npm run typecheck`, `npm run build`로 실행한다. 백엔드 테스트는 `backend/`에서 `mvn test`로 실행한다. 테스트별 DB·환경 요구사항을 확인하고 개발 DB를 사용한다.

공개 API 점검은 다음 명령으로 실행할 수 있다.

```powershell
.\scripts\test-market-quality.ps1 -BaseUrl 'http://localhost:8081/api'
```

이 러너는 `-FirebaseIdToken`으로 인증 API를, `-RunOrderScenarios`를 추가하면 실제 주문·취소 시나리오를 점검한다. `scripts/check-market-invariants.ps1`은 로컬 DB의 계좌·주문 정합성을 읽기 전용으로 점검한다.

다음은 기능 완성과 운영 확인을 위해 남겨 둔 체크리스트다. 이번 문서 수정에서 실행·통과한 항목을 표시하는 목록은 아니다.

- [ ] Google·이메일 로그인 → 계좌 조회 → 주문·부분 체결·취소 → 정산·손익까지 검증
- [ ] 동시 주문에서 현금·수량 중복 사용과 음수 잔고가 없는지 확인
- [ ] 서버 재시작 후 미체결 주문·예약금·정산·스트림 복구 확인
- [ ] 전 종목 호가 깊이·최근 체결·오류·응답 시간을 일정 시간 관찰
- [ ] 상·하한가, VI·서킷브레이커 발동·해제, 단일가 처리 검증
- [ ] 주간 시작·종료 수익률과 칭호 지급, 대출 이자·반대매매 검증
- [ ] 관리자 권한 거부와 거래 제한·해제·닉네임 조치 검증
- [ ] 뉴스 관련성·긍부정 분류를 표본 기사로 평가하고 추출 실패 확인
- [ ] 가격 변동 이유 API를 화면에 연결하고 페이지 미션 수행 확인 보완
- [ ] 실제 휴대폰의 인증·딥링크·주문·스크롤 확인 및 서명 APK 준비
- [ ] Firebase Hosting·OCI의 활성 커밋과 실제 서비스 동작을 각각 확인
