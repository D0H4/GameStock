# GameStock OpenAPI v1

외부 프로그램에서 GameStock의 모의 주식·ETF·선물·옵션 가격을 조회하고 기존 계좌로 거래하는 REST API입니다. 서버 주소 아래 `/api/openapi/v1`을 사용합니다.

- 명세: `GET /api/openapi/openapi.json` — OpenAPI 3.0.3, Postman 등에서 가져올 수 있습니다.
- 웹 문서: `/api-docs` — 상단 메뉴의 **API**에서 같은 내용을 복사 버튼과 함께 볼 수 있습니다.
- 명세 원본: [gamestock-v1.json](../backend/src/main/resources/openapi/gamestock-v1.json)
- Python 3.10+ 예제: [gamestock_openapi.py](../examples/gamestock_openapi.py), 추가 패키지 없음
- 이 기능은 백엔드 배포 후 제공됩니다. 새 테이블은 기존 시작 시 마이그레이션으로 생성됩니다.

## 인증과 키 관리

시세는 인증 없이 조회합니다. 계좌·주문 조회에는 `READ` 또는 `TRADE` 키, 주문·취소·청산에는 `TRADE` 키가 필요합니다.

키 관리는 로그인한 사용자의 **Firebase ID 토큰**으로만 가능합니다. 웹·모바일 Firebase 사용자의 `getIdToken()`으로 받은 토큰을 사용합니다. API 키로 다른 키를 발급할 수 없습니다.

웹에서는 상단 **API** 메뉴(`/api-docs`)에서 로그인한 뒤 키 이름·권한·만기를 선택하고 **API 키 발급**을 누릅니다. 발급된 키를 복사해 보관하세요. 같은 화면에서 키 목록을 확인하고 폐기할 수 있으며, 토큰을 직접 추출하거나 명령어를 실행할 필요가 없습니다.

직접 HTTP로 키를 관리하는 경우의 Fish 예제:

```fish
set -gx GAMESTOCK_API_URL http://localhost:8081
set -gx GAMESTOCK_FIREBASE_TOKEN '로그인한 사용자의 Firebase ID 토큰'

curl --fail-with-body "$GAMESTOCK_API_URL/api/openapi/keys" \
  -H "Authorization: Bearer $GAMESTOCK_FIREBASE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"my-bot","permission":"TRADE","expiresInDays":30}'
```

응답의 `token`을 저장하고 이후 요청의 `Authorization: Bearer <token>`에 넣습니다. 원문은 발급 응답에서 한 번만 제공하며, 서버에는 SHA-256 해시를 저장합니다. 기본 만기는 90일, 지정 가능 범위는 1~365일입니다. 계정당 활성 키는 최대 10개입니다.

```fish
set -gx GAMESTOCK_API_KEY '발급 응답의 token'

# 키 목록: 원문 없이 id·이름·접두사·권한·만기·폐기 시각
curl --fail-with-body "$GAMESTOCK_API_URL/api/openapi/keys" \
  -H "Authorization: Bearer $GAMESTOCK_FIREBASE_TOKEN"

# 본인 키 폐기: key-id를 목록의 id로 교체
curl --fail-with-body -X DELETE "$GAMESTOCK_API_URL/api/openapi/keys/key-id" \
  -H "Authorization: Bearer $GAMESTOCK_FIREBASE_TOKEN"
```

키 목록은 활성 키 우선으로 최대 100개를 반환합니다. 정지된 계정도 키 목록 조회와 폐기는 가능합니다. 정지 중에는 API 키를 통한 개인 기능을 사용할 수 없고, 관리자 계정은 거래할 수 없습니다. 키 폐기·만료·거래 권한은 큐에 들어간 주문의 실행 직전에도 재확인합니다. 이미 실행 중인 주문은 완료될 수 있습니다. 원격 서버에는 HTTPS를 사용하고 키를 URL이나 소스 저장소에 넣지 마세요.

## 시세 조회

아래 경로는 모두 `/api/openapi/v1` 기준이며 인증이 필요 없습니다.

| 메서드·경로 | 반환 내용 |
| --- | --- |
| `GET /quotes` | 모든 주식과 ETF·선물·옵션 스냅샷 |
| `GET /stocks` | 주식 목록·현재가·등락·거래 제한 |
| `GET /stocks/{symbol}` | 주식 한 종목의 현재가 |
| `GET /stocks/{symbol}/orderbook` | 주식 매수·매도 호가 |
| `GET /stocks/{symbol}/candles?range=1d` | 주식 OHLC 차트 |
| `GET /etfs` | ETF NAV·구성 종목·최근 표본 |
| `GET /futures` | `FUTIDX` 선물 가격·승수·레버리지 |
| `GET /options?cycleId=1H&type=CALL` | 옵션 체인; 필터는 생략 가능 |

종목 코드는 대문자입니다. 차트 기간은 `1h`, `6h`, `12h`, `1d`, `3d`, `1w`, `2w`, `1m`, `1y`, 각각의 봉 간격은 1분·5분·10분·30분·1시간·2시간·6시간·1일·1주입니다. 차트의 기존 `volume` 필드는 가격 표본·집계 건수이며 거래 주식 수가 아닙니다.

```fish
curl --fail-with-body "$GAMESTOCK_API_URL/api/openapi/v1/quotes"
curl --fail-with-body "$GAMESTOCK_API_URL/api/openapi/v1/stocks/GI/orderbook"
curl --fail-with-body "$GAMESTOCK_API_URL/api/openapi/v1/options?cycleId=1H&type=PUT"
```

주식은 기존 공개 캐시를 공유하고, 파생 스냅샷은 최대 1초 동안 공유합니다. 시세는 체결 가격을 보장하지 않으며 주문 시 서버가 다시 계산합니다. ETF·선물의 `series`는 서버 메모리의 최근 표본으로, 시각이 붙은 OHLC 이력이 아닙니다. 옵션 회차는 `10M`(10분), `1H`(1시간), `SUN`(일요일 18:00 한국시간)입니다.

주식·ETF 가격은 모의 원화입니다. 선물 `price`, 옵션 `mark`·`strike`는 지수 포인트이고, 선물 승수는 계약당 1포인트 = 1,000원입니다. 옵션 `premium`은 **계약당 원화**, `expiryEpoch`는 **초 단위 Unix 시각**입니다. 옵션 주문에는 최신 체인의 만기·종류·행사가를 사용합니다.

## 매수·매도

모든 신규 주문은 `POST /orders`입니다. `Authorization`과 `Idempotency-Key`가 필요합니다. 주식은 1~1,000주, 나머지는 1~100좌/계약을 주문할 수 있습니다. 수량에 소수를 보내면 거절합니다. 상품에 맞지 않는 필드나 알 수 없는 필드도 거절합니다.

| 상품 | `BUY` | `SELL` | 주문 방식 |
| --- | --- | --- | --- |
| `STOCK` | 주식 매수 | 보유 주식 매도 | `MARKET` 또는 `LIMIT` |
| `ETF` | ETF 매수 | 보유 ETF 매도 | 현재 NAV |
| `FUTURE` | 롱 신규 진입 | 숏 신규 진입 | 현재 지수, 레버리지 2·5·10 |
| `OPTION` | 콜/풋 매수 | 지정한 보유 옵션 매도 | 서버 계산 프리미엄 |

**선물의 `SELL`은 롱 청산이 아니라 새 숏 진입입니다.** 기존 선물 청산은 `/positions/{positionId}/close`를 사용합니다. 옵션 신규 매도(발행)는 지원하지 않습니다. 현재 UI와 같은 거래 범위입니다.

주식 시장가 매수:

```fish
curl --fail-with-body "$GAMESTOCK_API_URL/api/openapi/v1/orders" \
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \
  -H 'Idempotency-Key: stock-buy-unique-001' \
  -H 'Content-Type: application/json' \
  -d '{"product":"STOCK","symbol":"GI","side":"BUY","quantity":1,"orderType":"MARKET"}'
```

다른 주문은 같은 엔드포인트에 아래 본문을 사용하고, **새 주문마다 새로운 요청 키**를 지정합니다.

```json
{"product":"STOCK","symbol":"GI","side":"SELL","quantity":1,"orderType":"LIMIT","price":10000}
```

```json
{"product":"ETF","symbol":"ETFIDX","side":"BUY","quantity":2}
```

```json
{"product":"ETF","symbol":"ETFIDX","side":"SELL","quantity":1}
```

```json
{"product":"FUTURE","symbol":"FUTIDX","side":"BUY","quantity":1,"leverage":5}
```

```json
{"product":"FUTURE","symbol":"FUTIDX","side":"SELL","quantity":1,"leverage":2}
```

주식 `orderType` 생략 시 시장가, 선물 `leverage` 생략 시 5배입니다. `price`는 주식 지정가에서만 사용하고 실제 호가 단위·가격제한폭을 지켜야 합니다.

옵션은 `/options` 응답에서 받은 `type`, `cycleId`, `strike`, `expiryEpoch`를 전달합니다. 아래 값은 형식 예시이므로 최신 시세 값으로 교체합니다.

```json
{"product":"OPTION","side":"BUY","quantity":1,"optionType":"CALL","cycleId":"1H","strike":1000,"expiryEpoch":1792659600}
```

풋은 `optionType: "PUT"`입니다. 만기가 이미 바뀌었으면 요청을 거절하며 자동으로 다음 회차를 사지 않습니다. 보유 옵션 매도는 `/positions`에서 받은 id로 지정합니다.

```json
{"product":"OPTION","side":"SELL","quantity":1,"positionId":123}
```

성공 응답은 `201`과 `{ "requestId": "...", "operation": "ORDER", "result": { ... } }`입니다. 주식 `result`에는 `orderId`, 체결 상태, 수수료, 계좌 요약이 들어 있습니다. `OPEN`은 대기 또는 부분 체결이며, 접수 성공만으로 전량 체결을 뜻하지 않습니다. ETF·선물·옵션은 `result.account`에 갱신된 포지션과 거래 내역이 들어 있습니다.

## 계좌·주문 확인과 청산

아래 조회는 `READ` 또는 `TRADE` 키를 사용합니다. 소유자는 항상 API 키에서 결정합니다.

| 메서드·경로 | 반환 내용 |
| --- | --- |
| `GET /account` | 주식·파생 계좌 요약 |
| `GET /positions` | 보유 ETF·선물·옵션 포지션 전체 |
| `GET /trades` | ETF·선물·옵션 거래 최근 20건 |
| `GET /orders?symbol=GI&limit=50` | 주식 미체결 목록 |
| `GET /orders/{orderId}` | 본인 주식 주문의 현재 상태·잔여 수량 |
| `GET /orders/history?symbol=GI` | 종목별 주식 주문 이력 최근 20건 |
| `GET /requests/{requestId}` | 완료된 API 요청의 원래 응답 |

미체결 목록은 id 내림차순, `limit`는 1~100입니다. 다음 페이지는 마지막 행의 `id`를 `beforeId`로 전달합니다. `symbol`을 생략하면 전체 종목입니다.

`portfolio.cash`와 `derivatives.cash`는 **동일한 현금**입니다. 둘을 더하지 마세요. 파생 `equity`에는 주식 평가액이 포함되지 않습니다. 두 요약은 순차 조회하므로 다른 주문이 동시에 실행되면 기준 시각이 다를 수 있습니다. 거래 내역의 `cashFlow`는 수수료를 포함한 순현금 증감이며 `fee`를 다시 빼지 않습니다.

주식 미체결 취소(`TRADE` 키):

```fish
curl --fail-with-body -X DELETE "$GAMESTOCK_API_URL/api/openapi/v1/orders/123" \
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \
  -H 'Idempotency-Key: cancel-unique-001'
```

ETF·선물·옵션 부분/전량 청산(`TRADE` 키):

```fish
curl --fail-with-body "$GAMESTOCK_API_URL/api/openapi/v1/positions/123/close" \
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \
  -H 'Idempotency-Key: close-unique-001' \
  -H 'Content-Type: application/json' \
  -d '{"quantity":1}'
```

두 작업 모두 `200`과 요청 결과를 반환합니다. 청산 수량은 1~100 및 보유 수량 이하입니다. 이미 체결된 주식 주문은 취소할 수 없습니다.

## 중복 방지와 오류 처리

주문·취소·청산은 `Idempotency-Key`가 필수입니다. 영문·숫자·점·밑줄·하이픈 1~64자를 사용하며 UUID를 권장합니다. 키는 API 키별이 아닌 **계정별**로 구분합니다.

1. 새 요청의 키와 본문을 로컬에 먼저 기록합니다.
2. 시간 초과·연결 끊김·5xx가 발생하면 같은 키와 같은 본문으로 재시도합니다.
3. 성공한 요청은 최소 7일 동안 원래 응답을 돌려줍니다. 서버 재시작이나 동시 요청에도 주문을 다시 실행하지 않습니다.
4. 같은 키로 다른 본문 또는 다른 작업을 보내면 `409`입니다. 요청 키는 새 주문과 재사용하지 마세요.

주문 원장·잔액·재시도 응답은 같은 DB 트랜잭션으로 커밋합니다. 실패하여 롤백한 요청은 완료 결과를 남기지 않습니다. `/requests/{requestId}`의 `404`는 아직 처리 중일 때도 가능하므로 미실행을 단정하면 안 됩니다. 재시도 응답의 계좌는 **처음 완료된 시점**의 값입니다. 현재 상태는 `/account` 또는 `/orders/{orderId}`로 확인합니다. 7일 이후의 재시도 보호는 보장하지 않습니다.

오류는 `{ "code": "...", "message": "..." }` 형식입니다.

| 상태 | 의미 |
| --- | --- |
| `400 INVALID_REQUEST` | 필수값·수량·상품 조합·만기·주문 가능 잔액 등 검증 실패 |
| `401 UNAUTHORIZED` | 인증 누락, 잘못된 키, 만료·폐기된 키 |
| `403 FORBIDDEN` | 조회 전용 키의 거래, 계정 정지, 관리자 거래 |
| `404 NOT_FOUND` | 종목·본인 주문·포지션·완료 요청 없음 |
| `409 CONFLICT` | 요청 키 충돌 또는 활성 API 키 개수 초과 |
| `429 RATE_LIMITED` | 호출 한도 초과; `Retry-After` 초 뒤 재시도 |
| `500`, `503` | 처리 오류·일시 불가; 새 요청 키를 만들지 말고 기존 요청 결과 확인 |

서버 인스턴스별로 IP당 120회/분, 계정당 120회/분이며 거래 작업은 그중 30회/분입니다. 키를 여러 개 만들어도 계정 한도는 공유합니다. 기존 시장의 주문 빈도 제한·가격제한폭·수수료·잔액·정지 규칙도 적용됩니다. IP는 서블릿의 원격 주소를 사용하며 임의 `X-Forwarded-For`를 직접 신뢰하지 않습니다. 리버스 프록시 운영 시 신뢰할 프록시의 원격 주소 처리를 서버에 설정하지 않으면 같은 프록시 뒤 클라이언트가 IP 한도를 공유합니다.

## Python 사용

명령줄 예제는 데이터만 조회합니다. 환경변수는 실행 프로세스에서 읽으며 `.env` 파일을 읽지 않습니다.

```fish
python3 examples/gamestock_openapi.py --base-url "$GAMESTOCK_API_URL" quotes
python3 examples/gamestock_openapi.py --base-url "$GAMESTOCK_API_URL" account
```

프로그램에서 주문을 명시적으로 호출할 수 있습니다.

```python
import os
import uuid
from examples.gamestock_openapi import GameStockClient

api = GameStockClient(os.environ["GAMESTOCK_API_URL"], os.environ["GAMESTOCK_API_KEY"])
order = {"product": "ETF", "symbol": "ETFIDX", "side": "BUY", "quantity": 1}
request_id = str(uuid.uuid4())
# 이 request_id와 order를 먼저 영구 저장한 다음 전송하세요.
receipt = api.submit_order(order, request_id=request_id)
print(receipt)
# 재시도: api.submit_order(order, request_id=request_id)
```

`options()`, `account()`, `open_orders()`, `order_status()`, `close_position()`, `cancel_order()`, `receipt()`도 제공합니다. 자동 재시도나 자동 주문 루프는 포함하지 않습니다.

## 검증

```fish
cd backend
mvn test -Dtest=OpenApiControllerTest,OpenApiSpecTest
# 아래는 로컬 MySQL의 임시 openapi_test_* DB만 생성하고 삭제합니다.
mvn test -Dtest=OpenApiIntegrationTest \
  '-Dmarket.test.jdbc=jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true'
```

인증·만료·폐기·정지·관리자·읽기 전용 권한, 네 상품의 매수/매도와 부분 청산, 주식 주문 취소, 동시 중복 요청·재시작 후 재시도, 실패 시 트랜잭션 롤백을 검증합니다.
