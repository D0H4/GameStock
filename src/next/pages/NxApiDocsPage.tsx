import { useEffect, useRef, useState, type ReactNode } from "react";
import { apiBaseUrl, apiFetch } from "../../lib/api";
import { openLoginPrompt, useAuthUser } from "../../lib/auth";
import { firebaseAuth } from "../../lib/firebase";

const BASE = apiBaseUrl;
const SPEC_URL = `${BASE}/api/openapi/openapi.json`;

type Method = "GET" | "POST" | "DELETE";

type Endpoint = {
  method: Method;
  path: string;
  auth: "공개" | "READ / TRADE" | "TRADE" | "Firebase";
  description: string;
};

const QUOTE_ENDPOINTS: Endpoint[] = [
  { method: "GET", path: "/api/openapi/v1/quotes", auth: "공개", description: "모든 주식·ETF·선물·옵션 스냅샷" },
  { method: "GET", path: "/api/openapi/v1/stocks", auth: "공개", description: "주식 목록과 현재가" },
  { method: "GET", path: "/api/openapi/v1/stocks/{symbol}", auth: "공개", description: "주식 한 종목의 현재가" },
  { method: "GET", path: "/api/openapi/v1/stocks/{symbol}/orderbook", auth: "공개", description: "주식 매수·매도 호가" },
  { method: "GET", path: "/api/openapi/v1/stocks/{symbol}/candles?range=1d", auth: "공개", description: "주식 OHLC 차트" },
  { method: "GET", path: "/api/openapi/v1/etfs", auth: "공개", description: "ETF NAV·구성 종목·최근 표본" },
  { method: "GET", path: "/api/openapi/v1/futures", auth: "공개", description: "FUTIDX 선물 가격·승수·레버리지" },
  { method: "GET", path: "/api/openapi/v1/options?cycleId=1H&type=CALL", auth: "공개", description: "옵션 체인; 필터는 생략 가능" },
];

const KEY_ENDPOINTS: Endpoint[] = [
  { method: "POST", path: "/api/openapi/keys", auth: "Firebase", description: "API 키 발급; 토큰 원문은 이 응답에서 한 번만" },
  { method: "GET", path: "/api/openapi/keys", auth: "Firebase", description: "키 목록; 원문 없이 id·이름·접두사·권한·만기" },
  { method: "DELETE", path: "/api/openapi/keys/{keyId}", auth: "Firebase", description: "본인 키 폐기" },
  { method: "GET", path: "/api/openapi/openapi.json", auth: "공개", description: "OpenAPI 3.0.3 명세" },
];

const ACCOUNT_ENDPOINTS: Endpoint[] = [
  { method: "GET", path: "/api/openapi/v1/account", auth: "READ / TRADE", description: "주식·파생 계좌 요약" },
  { method: "GET", path: "/api/openapi/v1/positions", auth: "READ / TRADE", description: "보유 ETF·선물·옵션 포지션 전체" },
  { method: "GET", path: "/api/openapi/v1/trades", auth: "READ / TRADE", description: "ETF·선물·옵션 최근 거래 20건" },
  { method: "GET", path: "/api/openapi/v1/orders?symbol=GI&limit=50", auth: "READ / TRADE", description: "주식 미체결 주문 목록" },
  { method: "GET", path: "/api/openapi/v1/orders/{orderId}", auth: "READ / TRADE", description: "본인 주식 주문의 상태·잔여 수량" },
  { method: "GET", path: "/api/openapi/v1/orders/history?symbol=GI", auth: "READ / TRADE", description: "종목별 주식 주문 이력 최근 20건" },
  { method: "GET", path: "/api/openapi/v1/requests/{requestId}", auth: "READ / TRADE", description: "완료된 요청의 원래 응답" },
];

const TRADE_ENDPOINTS: Endpoint[] = [
  { method: "POST", path: "/api/openapi/v1/orders", auth: "TRADE", description: "주식·ETF·선물·옵션 신규 주문" },
  { method: "DELETE", path: "/api/openapi/v1/orders/{orderId}", auth: "TRADE", description: "주식 미체결 주문 취소" },
  { method: "POST", path: "/api/openapi/v1/positions/{positionId}/close", auth: "TRADE", description: "ETF·선물·옵션 부분/전량 청산" },
];

const ORDER_EXAMPLES = [
  {
    id: "stock",
    label: "주식",
    body: `{"product":"STOCK","symbol":"GI","side":"BUY","quantity":1,"orderType":"MARKET"}`,
    note: "지정가는 orderType을 LIMIT으로 바꾸고 price를 더합니다. 주식만 지정가를 지원하며 1~1,000주를 주문할 수 있습니다. price는 호가 단위와 가격제한폭을 지켜야 합니다.",
  },
  {
    id: "etf",
    label: "ETF",
    body: `{"product":"ETF","symbol":"ETFIDX","side":"BUY","quantity":2}`,
    note: "현재 NAV로 체결됩니다. 매도도 같은 형식에 side만 SELL로 바꿉니다.",
  },
  {
    id: "future",
    label: "선물",
    body: `{"product":"FUTURE","symbol":"FUTIDX","side":"BUY","quantity":1,"leverage":5}`,
    note: "레버리지는 2·5·10배이며 생략하면 5배입니다. SELL은 롱 청산이 아니라 새 숏 진입입니다.",
  },
  {
    id: "option",
    label: "옵션",
    body: `{"product":"OPTION","side":"BUY","quantity":1,"optionType":"CALL","cycleId":"1H","strike":1000,"expiryEpoch":1792659600}`,
    note: "값은 /options 응답의 최신 type·cycleId·strike·expiryEpoch로 바꿉니다. 만기가 바뀌면 자동 매수하지 않고 거절합니다.",
  },
] as const;

const ERROR_ROWS = [
  ["400", "INVALID_REQUEST", "필수값·수량·상품 조합·만기·주문 가능 잔액 등 검증 실패"],
  ["401", "UNAUTHORIZED", "인증 누락, 잘못된 키, 만료·폐기된 키"],
  ["403", "FORBIDDEN", "조회 전용 키의 거래, 계정 정지, 관리자 거래"],
  ["404", "NOT_FOUND", "종목·본인 주문·포지션·완료 요청 없음"],
  ["409", "CONFLICT", "요청 키 충돌 또는 활성 API 키 개수 초과"],
  ["429", "RATE_LIMITED", "호출 한도 초과; Retry-After 초 뒤 재시도"],
  ["500 · 503", "—", "처리 오류·일시 불가; 새 요청 키를 만들지 말고 요청 결과 확인"],
] as const;

const TOC = [
  ["quick-start", "빠른 시작"],
  ["auth", "인증과 키"],
  ["quotes", "시세 조회"],
  ["orders", "주문하기"],
  ["account", "계좌·주문 조회"],
  ["close", "취소·청산"],
  ["idempotency", "중복 방지"],
  ["errors", "오류·호출 한도"],
  ["tools", "명세와 도구"],
] as const;

function CopyButton({ text, label = "복사" }: { text: string; label?: string }) {
  const [status, setStatus] = useState<"idle" | "copied" | "failed">("idle");
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setStatus("copied");
    } catch {
      setStatus("failed");
    }
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setStatus("idle"), 1600);
  };

  const textLabel = status === "copied" ? "복사됨" : status === "failed" ? "복사 실패" : label;
  return (
    <button
      type="button"
      className="nx-docs-copy"
      onClick={() => void copy()}
      aria-label={label === "복사" ? "복사" : `${label} 복사`}
    >
      {textLabel}
    </button>
  );
}

function CodeBlock({ title, code }: { title?: string; code: string }) {
  return (
    <figure className="nx-docs-code">
      {title && <figcaption>{title}</figcaption>}
      <div className="nx-docs-code-body">
        <pre><code>{code}</code></pre>
        <CopyButton text={code} />
      </div>
    </figure>
  );
}

function EndpointTable({ label, endpoints }: { label: string; endpoints: Endpoint[] }) {
  return (
    <div className="nx-docs-table-wrap">
      <table className="nx-docs-table" aria-label={label}>
        <thead>
          <tr>
            <th scope="col">메서드</th>
            <th scope="col">경로</th>
            <th scope="col">인증</th>
            <th scope="col">설명</th>
          </tr>
        </thead>
        <tbody>
          {endpoints.map((endpoint) => (
            <tr key={`${endpoint.method} ${endpoint.path}`}>
              <td data-label="메서드"><span className={`nx-docs-method is-${endpoint.method.toLowerCase()}`}>{endpoint.method}</span></td>
              <td data-label="경로"><code className="nx-docs-path">{endpoint.path}</code></td>
              <td data-label="인증"><span className="nx-docs-auth">{endpoint.auth}</span></td>
              <td data-label="설명">{endpoint.description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Section({ id, title, lede, children }: { id: string; title: string; lede?: string; children: ReactNode }) {
  return (
    <section id={id} className="nx-docs-section" aria-labelledby={`${id}-title`}>
      <h2 id={`${id}-title`} className="nx-docs-section-title">{title}</h2>
      {lede && <p className="nx-docs-lede">{lede}</p>}
      {children}
    </section>
  );
}

function OrderExamples() {
  const [active, setActive] = useState<(typeof ORDER_EXAMPLES)[number]["id"]>("stock");
  const example = ORDER_EXAMPLES.find((item) => item.id === active) ?? ORDER_EXAMPLES[0];

  return (
    <div className="nx-docs-examples">
      <div className="nx-seg" aria-label="상품별 주문 본문">
        {ORDER_EXAMPLES.map((item) => (
          <button
            key={item.id}
            type="button"
            className={item.id === active ? "is-active" : ""}
            aria-pressed={item.id === active}
            onClick={() => setActive(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>
      <CodeBlock title="요청 본문" code={example.body} />
      <p className="nx-docs-note-inline">{example.note}</p>
    </div>
  );
}

type KeyInfo = {
  id: string; name: string; prefix: string; permission: "READ" | "TRADE";
  createdAt: string; expiresAt: string; revokedAt: string | null;
};
type IssuedKey = { key: KeyInfo; token: string };

function ApiKeyManager() {
  const { user, ready } = useAuthUser();
  if (!ready) return <p role="status">로그인 확인 중…</p>;
  if (!user) return <button className="nx-btn is-primary" type="button" onClick={() => openLoginPrompt("/api-docs")}>로그인하고 API 키 발급</button>;
  return <AccountKeys key={user.uid} uid={user.uid} />;
}

function AccountKeys({ uid }: { uid: string }) {
  const [keys, setKeys] = useState<KeyInfo[]>([]);
  const [issued, setIssued] = useState<IssuedKey | null>(null);
  const [name, setName] = useState("my-bot");
  const [permission, setPermission] = useState<"READ" | "TRADE">("READ");
  const [days, setDays] = useState("90");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const locked = useRef(false);
  const mounted = useRef(false);
  const current = () => mounted.current && firebaseAuth.currentUser?.uid === uid;

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError("");
    apiFetch<KeyInfo[]>("/api/openapi/keys", { signal: controller.signal })
      .then((items) => { if (!controller.signal.aborted && current()) setKeys(items); })
      .catch((cause: unknown) => { if (!controller.signal.aborted && current()) setError(cause instanceof Error ? cause.message : "키 목록을 불러오지 못했습니다."); })
      .finally(() => { if (!controller.signal.aborted && current()) setLoading(false); });
    return () => controller.abort();
  }, [uid, reload]);

  const mutate = async (keyId?: string) => {
    if (locked.current || !current()) return;
    if (!keyId && (!name.trim() || !Number.isInteger(Number(days)) || Number(days) < 1 || Number(days) > 365)) {
      setError("키 이름과 만기(1~365일)를 확인해 주세요.");
      return;
    }
    locked.current = true;
    setBusy(true);
    setError("");
    try {
      if (keyId) {
        await apiFetch<void>(`/api/openapi/keys/${encodeURIComponent(keyId)}`, { method: "DELETE" });
        if (!current()) return;
        setKeys((items) => items.map((item) => item.id === keyId ? { ...item, revokedAt: new Date().toISOString() } : item));
        setIssued((value) => value?.key.id === keyId ? null : value);
        setConfirmId(null);
      } else {
        const result = await apiFetch<IssuedKey>("/api/openapi/keys", {
          method: "POST", body: JSON.stringify({ name: name.trim(), permission, expiresInDays: Number(days) }),
        });
        if (!current()) return;
        setIssued(result);
        setKeys((items) => [result.key, ...items]);
      }
    } catch (cause) {
      if (current()) setError(cause instanceof Error ? `${cause.message} 목록을 새로고침해 처리 결과를 확인해 주세요.` : "요청을 완료하지 못했습니다. 목록을 새로고침해 확인해 주세요.");
    } finally {
      locked.current = false;
      if (current()) setBusy(false);
    }
  };

  return <div>
    <form onSubmit={(event) => { event.preventDefault(); void mutate(); }}>
      <fieldset disabled={busy || loading || issued !== null}>
        <legend>새 API 키</legend>
        <div className="nx-field"><label htmlFor="api-key-name">키 이름</label><input className="nx-input" id="api-key-name" required maxLength={80} value={name} onChange={(event) => setName(event.target.value)} /></div>
        <div className="nx-field"><label htmlFor="api-key-permission">권한</label><select className="nx-input" id="api-key-permission" value={permission} onChange={(event) => setPermission(event.target.value as "READ" | "TRADE")}><option value="READ">조회 전용</option><option value="TRADE">조회 + 주문</option></select></div>
        <div className="nx-field"><label htmlFor="api-key-days">만기 (일)</label><input className="nx-input" id="api-key-days" type="number" required min={1} max={365} step={1} value={days} onChange={(event) => setDays(event.target.value)} /></div>
        <button className="nx-btn is-primary" type="submit">{busy ? "처리 중…" : "API 키 발급"}</button>
      </fieldset>
    </form>
    {error && <p role="alert" className="nx-field-hint is-error">{error}</p>}
    {issued && <div role="status">
      <p>키가 발급되었습니다. 원문은 다시 조회할 수 없으니 지금 복사해 보관하세요.</p>
      <CodeBlock title={`${issued.key.name} API 키`} code={issued.token} />
      <button className="nx-btn" type="button" onClick={() => setIssued(null)}>보관 완료 · 키 숨기기</button>
    </div>}
    <h3>내 API 키</h3>
    <button className="nx-btn" type="button" disabled={loading || busy} onClick={() => setReload((value) => value + 1)}>목록 새로고침</button>
    {loading ? <p role="status">키 목록을 불러오는 중…</p> : keys.length === 0 ? <p>발급된 키가 없습니다.</p> : <div className="nx-docs-table-wrap"><table className="nx-docs-table" aria-label="내 API 키"><thead><tr><th scope="col">이름 / 접두사</th><th scope="col">권한</th><th scope="col">만기</th><th scope="col">상태 / 관리</th></tr></thead><tbody>
      {keys.map((key) => <tr key={key.id}>
        <td>{key.name}<br /><code>{key.prefix}…</code></td><td>{key.permission === "READ" ? "조회" : "조회 + 주문"}</td><td>{new Date(key.expiresAt).toLocaleString("ko-KR")}</td>
        <td>{key.revokedAt ? "폐기됨" : Date.parse(key.expiresAt) <= Date.now() ? "만료됨" : confirmId === key.id ? <><p>폐기하면 이 키로 API를 호출할 수 없습니다.</p><button className="nx-btn" type="button" disabled={busy || loading} onClick={() => void mutate(key.id)}>폐기 확인</button><button className="nx-btn" type="button" disabled={busy} onClick={() => setConfirmId(null)}>취소</button></> : <button className="nx-btn" type="button" disabled={busy || loading} aria-label={`${key.name} 키 폐기`} onClick={() => setConfirmId(key.id)}>폐기</button>}</td>
      </tr>)}
    </tbody></table></div>}
  </div>;
}

export function NxApiDocsPage() {
  const quoteCurl = `curl --fail-with-body "${BASE}/api/openapi/v1/quotes"`;
  const candleCurl = `curl --fail-with-body "${BASE}/api/openapi/v1/stocks/GI/candles?range=1d"`;

  const keyListCurl = `curl --fail-with-body "${BASE}/api/openapi/keys" \\
  -H "Authorization: Bearer $FIREBASE_ID_TOKEN"`;

  const keyRevokeCurl = `curl --fail-with-body -X DELETE "${BASE}/api/openapi/keys/KEY_ID" \\
  -H "Authorization: Bearer $FIREBASE_ID_TOKEN"`;

  const orderCurl = `curl --fail-with-body "${BASE}/api/openapi/v1/orders" \\
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \\
  -H "Idempotency-Key: stock-buy-001" \\
  -H "Content-Type: application/json" \\
  -d '{"product":"STOCK","symbol":"GI","side":"BUY","quantity":1,"orderType":"MARKET"}'`;

  const cancelCurl = `curl --fail-with-body -X DELETE "${BASE}/api/openapi/v1/orders/123" \\
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \\
  -H "Idempotency-Key: cancel-unique-001"`;

  const closeCurl = `curl --fail-with-body "${BASE}/api/openapi/v1/positions/123/close" \\
  -H "Authorization: Bearer $GAMESTOCK_API_KEY" \\
  -H "Idempotency-Key: close-unique-001" \\
  -H "Content-Type: application/json" \\
  -d '{"quantity":1}'`;

  return (
    <div className="nx-screen nx-docs">
      <header className="nx-docs-hero">
        <p className="nx-docs-kicker">OPENAPI · V1</p>
        <h1 className="nx-page-title">게임주 OpenAPI</h1>
        <p className="nx-docs-hero-copy">
          외부 프로그램에서 슈엔증권의 모의 주식·ETF·선물·옵션 시세를 읽고, 같은 계좌로 주문할 수 있는 REST API입니다.
          시세는 누구나 조회할 수 있고, 계좌와 주문은 발급받은 API 키로 인증합니다.
        </p>
        <div className="nx-docs-actions">
          <a className="nx-btn is-primary" href={SPEC_URL} target="_blank" rel="noreferrer">OpenAPI 명세 열기</a>
          <a className="nx-btn" href="#quick-start">빠른 시작</a>
        </div>
        <p className="nx-docs-disclaimer">모의 투자 API입니다. 실제 화폐·금융상품과 관계없습니다.</p>
      </header>

      <div className="nx-docs-layout">
        <nav className="nx-docs-toc" aria-label="문서 목차">
          <p className="nx-docs-toc-title">이 문서에서</p>
          <ol>
            {TOC.map(([id, label]) => (
              <li key={id}><a href={`#${id}`}>{label}</a></li>
            ))}
          </ol>
        </nav>

        <div className="nx-docs-body">
          <Section id="quick-start" title="API 키 발급" lede="로그인한 계정으로 키를 발급하고 관리합니다. 이름·권한·만기를 선택하세요.">
            <ApiKeyManager />
            <p className="nx-docs-note-inline">기본 만기는 90일이며 계정당 활성 키는 최대 10개입니다. 관리자 계정은 조회 전용 키만 발급할 수 있습니다.</p>
            <h3>발급받은 키로 요청하기</h3>
            <p>개인 요청의 <code>Authorization</code> 헤더에 <code>Bearer &lt;발급받은 키&gt;</code>를 넣습니다. 시세는 인증 없이 조회할 수 있습니다.</p>
            <CodeBlock title="전체 시세 조회" code={quoteCurl} />
          </Section>

          <Section id="auth" title="인증과 키" lede="키는 계정 단위로 관리합니다. 요청의 소유자는 항상 키에서 결정되며, 다른 사용자의 계좌를 지정할 수 없습니다.">
            <div className="nx-docs-facts">
              <div>
                <span>기준 URL</span>
                <code>{BASE}</code>
                <CopyButton text={BASE} label="URL" />
              </div>
              <div>
                <span>응답</span>
                <strong>JSON · UTF-8</strong>
              </div>
              <div>
                <span>인증</span>
                <strong>Authorization: Bearer</strong>
              </div>
              <div>
                <span>명세</span>
                <a href={SPEC_URL} target="_blank" rel="noreferrer">OpenAPI 3.0.3</a>
              </div>
            </div>

            <EndpointTable label="API 키 관리 엔드포인트" endpoints={KEY_ENDPOINTS} />
            <div className="nx-docs-two-col">
              <CodeBlock title="키 목록" code={keyListCurl} />
              <CodeBlock title="키 폐기" code={keyRevokeCurl} />
            </div>

            <div className="nx-docs-callout">
              <strong>권한과 계정 상태</strong>
              <ul>
                <li><code>READ</code> 키는 시세·계좌·주문 조회만 할 수 있습니다.</li>
                <li><code>TRADE</code> 키만 주문·취소·청산을 실행할 수 있습니다.</li>
                <li>정지된 계정도 키 목록 조회와 폐기는 가능하지만, 키를 통한 개인 기능은 사용할 수 없습니다.</li>
                <li>관리자 계정은 API로 거래할 수 없고 <code>TRADE</code> 키도 발급할 수 없습니다.</li>
              </ul>
            </div>
            <p className="nx-docs-note-inline">원격 서버에는 HTTPS를 사용하고, 키를 URL·로그·소스 저장소에 남기지 마세요. 키를 폐기하거나 만료시킨 뒤에는 같은 키로 요청할 수 없습니다.</p>
          </Section>

          <Section id="quotes" title="시세 조회" lede="아래 경로는 모두 인증이 필요 없습니다. 종목 코드는 대문자입니다.">
            <EndpointTable label="시세 조회 엔드포인트" endpoints={QUOTE_ENDPOINTS} />
            <div className="nx-docs-two-col">
              <CodeBlock title="전체 시세" code={quoteCurl} />
              <CodeBlock title="차트" code={candleCurl} />
            </div>
            <div className="nx-docs-callout">
              <strong>차트와 파생상품 읽는 법</strong>
              <ul>
                <li>차트 기간은 <code>1h</code>, <code>6h</code>, <code>12h</code>, <code>1d</code>, <code>1w</code>, <code>1m</code>, <code>1y</code>이고, 봉 간격은 각각 1분·5분·10분·30분·2시간·1일·1주입니다.</li>
                <li>차트의 <code>volume</code>은 거래 주식 수가 아니라 가격 표본·집계 건수입니다.</li>
                <li>선물 <code>price</code>와 옵션 <code>mark</code>·<code>strike</code>는 지수 포인트입니다. 선물 1포인트는 계약당 1,000원입니다.</li>
                <li>옵션 <code>premium</code>은 계약당 원화, <code>expiryEpoch</code>는 초 단위 Unix 시각입니다. 회차는 <code>10M</code>·<code>1H</code>·<code>SUN</code>입니다.</li>
                <li>ETF·선물의 <code>series</code>는 서버 메모리의 최근 표본이며, 시각이 붙은 OHLC 이력이 아닙니다.</li>
              </ul>
            </div>
            <p className="nx-docs-note-inline">시세는 체결 가격을 보장하지 않습니다. 주문 시 서버가 최신 가격과 잔액을 다시 계산합니다.</p>
          </Section>

          <Section id="orders" title="주문하기" lede="모든 신규 주문은 POST /api/openapi/v1/orders 하나로 보냅니다. Authorization과 Idempotency-Key가 필요합니다.">
            <EndpointTable label="주문·취소·청산 엔드포인트" endpoints={TRADE_ENDPOINTS} />
            <div className="nx-docs-table-wrap">
              <table className="nx-docs-table" aria-label="상품별 주문 방향">
                <thead>
                  <tr><th scope="col">상품</th><th scope="col">BUY</th><th scope="col">SELL</th><th scope="col">주문 방식</th></tr>
                </thead>
                <tbody>
                  <tr><td data-label="상품"><code>STOCK</code></td><td data-label="BUY">주식 매수</td><td data-label="SELL">보유 주식 매도</td><td data-label="주문 방식">MARKET 또는 LIMIT</td></tr>
                  <tr><td data-label="상품"><code>ETF</code></td><td data-label="BUY">ETF 매수</td><td data-label="SELL">보유 ETF 매도</td><td data-label="주문 방식">현재 NAV</td></tr>
                  <tr><td data-label="상품"><code>FUTURE</code></td><td data-label="BUY">롱 신규 진입</td><td data-label="SELL">숏 신규 진입</td><td data-label="주문 방식">현재 지수, 레버리지 2·5·10</td></tr>
                  <tr><td data-label="상품"><code>OPTION</code></td><td data-label="BUY">콜/풋 매수</td><td data-label="SELL">보유 옵션 매도</td><td data-label="주문 방식">서버 계산 프리미엄</td></tr>
                </tbody>
              </table>
            </div>

            <div className="nx-docs-callout is-guard">
              <strong>선물의 SELL은 청산이 아닙니다</strong>
              <p>기존 선물 포지션을 닫으려면 <code>/positions/&#123;positionId&#125;/close</code>를 사용하세요. 옵션 신규 매도(발행)는 지원하지 않습니다.</p>
            </div>

            <OrderExamples />

            <CodeBlock title="주식 시장가 주문" code={orderCurl} />
            <p className="nx-docs-note-inline">수량은 주식 1~1,000주, ETF·선물·옵션 1~100좌/계약입니다. 상품에 맞지 않는 필드나 알 수 없는 필드가 있으면 거절합니다. 주식 <code>orderType</code>을 생략하면 시장가, 선물 <code>leverage</code>를 생략하면 5배입니다.</p>

            <div className="nx-docs-callout">
              <strong>옵션 매도는 보유 포지션으로 지정합니다</strong>
              <CodeBlock code={`{"product":"OPTION","side":"SELL","quantity":1,"positionId":123}`} />
            </div>

            <p className="nx-docs-note-inline">성공하면 <code>201</code>과 <code>&#123; requestId, operation, result &#125;</code>를 돌려줍니다. 주식의 <code>OPEN</code>은 대기 또는 부분 체결이며, 접수만으로 전량 체결을 뜻하지 않습니다.</p>
          </Section>

          <Section id="account" title="계좌·주문 조회" lede="아래 조회는 READ 또는 TRADE 키를 사용합니다. 소유자는 항상 API 키에서 결정됩니다.">
            <EndpointTable label="계좌와 주문 조회 엔드포인트" endpoints={ACCOUNT_ENDPOINTS} />
            <div className="nx-docs-callout">
              <strong>잔고를 읽을 때 주의할 점</strong>
              <ul>
                <li><code>portfolio.cash</code>와 <code>derivatives.cash</code>는 같은 현금입니다. 둘을 더하지 마세요.</li>
                <li>파생 <code>equity</code>에는 주식 평가액이 포함되지 않습니다.</li>
                <li>두 요약은 순차 조회이므로 동시에 다른 주문이 체결되면 기준 시각이 다를 수 있습니다.</li>
                <li>거래 내역의 <code>cashFlow</code>는 수수료를 포함한 순현금 증감입니다. <code>fee</code>를 다시 빼지 마세요.</li>
                <li>미체결 목록은 id 내림차순이며 <code>limit</code>은 1~100입니다. 다음 페이지는 마지막 행의 <code>id</code>를 <code>beforeId</code>로 보냅니다.</li>
              </ul>
            </div>
          </Section>

          <Section id="close" title="취소·청산" lede="주식 미체결 주문은 DELETE, ETF·선물·옵션 포지션은 close 엔드포인트로 정리합니다. 둘 다 TRADE 키와 Idempotency-Key가 필요합니다.">
            <div className="nx-docs-two-col">
              <CodeBlock title="주식 주문 취소" code={cancelCurl} />
              <CodeBlock title="포지션 청산" code={closeCurl} />
            </div>
            <p className="nx-docs-note-inline">청산 수량은 1~100이면서 보유 수량 이하여야 합니다. 이미 체결된 주식 주문은 취소할 수 없습니다. 두 작업 모두 성공 시 <code>200</code>과 요청 결과를 반환합니다.</p>
          </Section>

          <Section id="idempotency" title="중복 방지" lede="주문·취소·청산은 네트워크 오류로 다시 보내도 한 번만 실행되어야 합니다. Idempotency-Key가 그 기준입니다.">
            <ol className="nx-docs-steps is-compact">
              <li>새 요청의 키와 본문을 로컬에 먼저 기록합니다.</li>
              <li>시간 초과·연결 끊김·5xx가 나면 <strong>같은 키와 같은 본문</strong>으로 재시도합니다.</li>
              <li>성공한 요청은 최소 7일 동안 원래 응답을 돌려줍니다. 서버 재시작이나 동시 요청에도 주문을 다시 실행하지 않습니다.</li>
              <li>같은 키로 다른 본문이나 다른 작업을 보내면 <code>409 CONFLICT</code>입니다.</li>
            </ol>
            <div className="nx-docs-two-col">
              <CodeBlock title="권장 형식" code={`Idempotency-Key: 6f9d2c1a-4a0e-4b77-9f5e-2c4d8f0b9a11`} />
              <CodeBlock title="규칙" code={`영문·숫자·점·밑줄·하이픈 1~64자\n계정별로 구분 · 새 주문마다 새 키`} />
            </div>
            <p className="nx-docs-note-inline">실패하여 롤백한 요청은 완료 결과를 남기지 않습니다. <code>/requests/&#123;requestId&#125;</code>의 <code>404</code>는 아직 처리 중일 때도 나올 수 있으므로 미실행으로 단정하지 마세요. 재시도 응답의 계좌는 처음 완료된 시점의 값입니다. 현재 상태는 <code>/account</code> 또는 <code>/orders/&#123;orderId&#125;</code>로 확인합니다.</p>
          </Section>

          <Section id="errors" title="오류·호출 한도" lede="오류는 항상 { code, message } 형식입니다. message는 사람이 읽는 설명이며 분기 기준으로 쓰지 마세요.">
            <div className="nx-docs-table-wrap">
              <table className="nx-docs-table" aria-label="오류 코드">
                <thead>
                  <tr><th scope="col">상태</th><th scope="col">코드</th><th scope="col">의미</th></tr>
                </thead>
                <tbody>
                  {ERROR_ROWS.map(([status, code, meaning]) => (
                    <tr key={status}>
                      <td className="nx-num" data-label="상태">{status}</td>
                      <td data-label="코드">{code === "—" ? "—" : <code>{code}</code>}</td>
                      <td data-label="의미">{meaning}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="nx-docs-callout">
              <strong>호출 한도</strong>
              <p>서버 인스턴스별로 IP당 분당 120회, 계정당 분당 120회이며, 거래 작업은 그중 분당 30회입니다. 키를 여러 개 만들어도 계정 한도는 공유합니다.</p>
              <p>IP는 서버가 확인한 원격 주소를 사용하며 임의의 <code>X-Forwarded-For</code>를 신뢰하지 않습니다. 리버스 프록시 뒤에서는 신뢰할 프록시 주소를 서버에 설정하지 않으면 클라이언트가 IP 한도를 공유할 수 있습니다.</p>
              <p><code>429</code> 응답의 <code>Retry-After</code> 초만큼 기다린 뒤 재시도하세요. 기존 시장의 주문 빈도 제한·가격제한폭·수수료·잔액·정지 규칙도 그대로 적용됩니다.</p>
            </div>
          </Section>

          <Section id="tools" title="명세와 도구" lede="OpenAPI 3.0.3 명세를 그대로 가져가 Postman·Insomnia·Swagger UI나 클라이언트 생성기에 넣을 수 있습니다.">
            <div className="nx-docs-tools">
              <div className="nx-docs-tool">
                <h3>명세 URL</h3>
                <code>{SPEC_URL}</code>
                <div className="nx-docs-tool-actions">
                  <a className="nx-btn is-small" href={SPEC_URL} target="_blank" rel="noreferrer">열기</a>
                  <CopyButton text={SPEC_URL} label="URL" />
                </div>
              </div>
              <div className="nx-docs-tool">
                <h3>인증 헤더</h3>
                <code>Authorization: Bearer gs_...</code>
                <p>시세 조회에는 필요 없습니다.</p>
              </div>
              <div className="nx-docs-tool">
                <h3>콘텐츠 형식</h3>
                <code>Content-Type: application/json</code>
                <p>주문·취소·청산 요청에 필요합니다.</p>
              </div>
            </div>
            <p className="nx-docs-note-inline">명세를 URL로 가져오면 서버 주소가 API 서버로 자동 설정됩니다. 파일로 내려받아 쓸 때는 서버 주소를 위 기준 URL로 지정하세요. 다른 환경에서 시험할 때는 <code>VITE_API_BASE_URL</code>로 프론트엔드의 API 주소를 바꿀 수 있습니다.</p>
          </Section>
        </div>
      </div>
    </div>
  );
}
