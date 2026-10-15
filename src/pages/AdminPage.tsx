import { useCallback, useEffect, useState } from "react";
import { Panel } from "../components/Panel";
import { apiFetch } from "../lib/api";
import { useProfile } from "../lib/profile";

type AdminUser = {
  id: number;
  uid: string | null;
  email: string | null;
  nickname: string;
  role: string;
  cash: number;
  createdAt: string | null;
  nicknameChangedAt: string | null;
  tradeBannedUntil: string | null;
  tradeBanReason: string | null;
};

const BAN_PRESETS = [1, 3, 7, 30] as const;

const formatTime = (value: string | null) =>
  value ? new Date(value).toLocaleString("ko-KR", { timeZone: "Asia/Seoul", dateStyle: "short", timeStyle: "short" }) : "-";

/** 관리자 전용 화면. 서버가 모든 요청에서 ADMIN 권한을 다시 확인한다. */
export function AdminPage() {
  const profile = useProfile();

  if (!profile) return <p className="empty" role="status">권한을 확인하고 있어요.</p>;
  if (!profile.admin) return <p className="empty" role="alert">관리자만 볼 수 있는 페이지입니다.</p>;

  return (
    <div className="page-stack">
      <h1 className="page-title">관리자</h1>
      <AdminUsers />
    </div>
  );
}

function AdminUsers() {
  const [query, setQuery] = useState("");
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState("");
  const [selectedId, setSelectedId] = useState<number | null>(null);

  const search = useCallback(async (q: string) => {
    setLoading(true);
    setMessage("");
    try {
      setUsers(await apiFetch<AdminUser[]>(`/api/admin/users${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ""}`));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "계정 목록을 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void search(""); }, [search]);

  const replace = (next: AdminUser) => setUsers((list) => list.map((user) => (user.id === next.id ? next : user)));
  const selected = users.find((user) => user.id === selectedId) ?? null;

  return (
    <div className="account-settings admin-page">
      <Panel id="admin-users" title="계정 · 닉네임 조회" meta={loading ? "불러오는 중" : `${users.length}명`} level={3}>
        <form className="account-form" onSubmit={(event) => { event.preventDefault(); void search(query); }}>
          <label htmlFor="admin-search">이메일 · 닉네임 · UID · 계정 번호</label>
          <div className="account-input-row">
            <input id="admin-search" value={query} placeholder="비워 두면 최근 가입 50명" onChange={(event) => setQuery(event.target.value)} />
            <button type="submit" className="account-button is-primary" disabled={loading}>검색</button>
          </div>
        </form>
        {message && <p className="account-message" role="status">{message}</p>}
        <div className="table-scroll admin-table-scroll">
          <table className="table admin-table">
            <thead>
              <tr>
                <th scope="col">번호</th>
                <th scope="col">로그인 계정</th>
                <th scope="col">닉네임</th>
                <th scope="col">가입</th>
                <th scope="col">거래</th>
                <th scope="col"><span className="vh">관리</span></th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id} className={user.id === selectedId ? "is-selected" : undefined}>
                  <td className="num">{user.id}</td>
                  <td>
                    <span className="admin-email">{user.email || "(이메일 없음)"}</span>
                    <small className="admin-uid" title={user.uid ?? ""}>{user.uid}</small>
                  </td>
                  <td>
                    {user.nickname}
                    {user.role === "ADMIN" && <span className="admin-badge">관리자</span>}
                  </td>
                  <td className="num">{formatTime(user.createdAt)}</td>
                  <td>
                    {user.tradeBannedUntil
                      ? <span className="admin-banned" title={user.tradeBanReason ?? ""}>~{formatTime(user.tradeBannedUntil)}</span>
                      : <span className="admin-ok">가능</span>}
                  </td>
                  <td>
                    <button type="button" className="text-button" aria-expanded={user.id === selectedId}
                      onClick={() => setSelectedId(user.id === selectedId ? null : user.id)}>
                      {user.id === selectedId ? "닫기" : "관리"}
                    </button>
                  </td>
                </tr>
              ))}
              {!loading && users.length === 0 && (
                <tr><td colSpan={6} className="empty">검색 결과가 없습니다.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </Panel>
      {selected && <AdminUserActions key={selected.id} user={selected} onChange={replace} />}
    </div>
  );
}

function AdminUserActions({ user, onChange }: { user: AdminUser; onChange: (user: AdminUser) => void }) {
  const [days, setDays] = useState("7");
  const [reason, setReason] = useState("부계정 의심");
  const [nickname, setNickname] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const run = async (action: () => Promise<AdminUser>, done: string) => {
    setBusy(true);
    setMessage("");
    try {
      onChange(await action());
      setMessage(done);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "처리하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const ban = () => {
    const value = Number(days);
    if (!Number.isInteger(value) || value < 1 || value > 365) {
      setMessage("제한 기간은 1~365일로 입력해 주세요.");
      return;
    }
    if (!window.confirm(`${user.nickname} 계정의 거래를 ${value}일 동안 제한할까요?\n제한 중에는 모든 서비스를 이용할 수 없고, 미체결 주문은 모두 취소됩니다.`)) return;
    void run(() => apiFetch<AdminUser>(`/api/admin/users/${user.id}/trade-ban`, {
      method: "PUT",
      body: JSON.stringify({ days: value, reason }),
    }), `${value}일 거래 제한을 걸었습니다.`);
  };

  const lift = () => {
    if (!window.confirm(`${user.nickname} 계정의 거래 제한을 풀까요?`)) return;
    void run(() => apiFetch<AdminUser>(`/api/admin/users/${user.id}/trade-ban`, { method: "DELETE" }), "거래 제한을 해제했습니다.");
  };

  const rename = () => {
    const value = nickname.trim();
    if (value && (value.length < 2 || value.length > 10)) {
      setMessage("닉네임은 2~10자로 입력해 주세요.");
      return;
    }
    const label = value || "자동 생성 닉네임";
    if (!window.confirm(`"${user.nickname}" 닉네임을 "${label}"(으)로 바꿀까요?`)) return;
    void run(async () => {
      const next = await apiFetch<AdminUser>(`/api/admin/users/${user.id}/nickname`, {
        method: "PUT",
        body: JSON.stringify({ nickname: value }),
      });
      setNickname("");
      return next;
    }, "닉네임을 변경했습니다.");
  };

  return (
    <Panel id="admin-user-actions" title={`${user.nickname} 관리`} meta={`#${user.id} · ${user.email || "이메일 없음"}`} level={3}>
      {message && <p className="account-message" role="status">{message}</p>}
      <div className="admin-actions">
        <section className="account-form" aria-labelledby="admin-ban-title">
          <h4 id="admin-ban-title" className="admin-action-title">거래 제한</h4>
          {user.tradeBannedUntil ? (
            <>
              <p className="account-hint">
                {formatTime(user.tradeBannedUntil)}까지 모든 서비스 이용이 정지되어 있습니다.
                {user.tradeBanReason && <> 사유: {user.tradeBanReason}</>}
              </p>
              <div className="account-input-row">
                <button type="button" className="account-button" disabled={busy} onClick={lift}>제한 해제</button>
              </div>
            </>
          ) : (
            <p className="account-hint">새 주문만 막습니다. 이미 넣은 주문 취소는 가능합니다.</p>
          )}
          <div className="admin-presets" role="group" aria-label="제한 기간 빠른 선택">
            {BAN_PRESETS.map((preset) => (
              <button key={preset} type="button" className={`text-button${days === String(preset) ? " is-active" : ""}`}
                aria-pressed={days === String(preset)} onClick={() => setDays(String(preset))}>{preset}일</button>
            ))}
          </div>
          <label htmlFor="admin-ban-days">기간(일)</label>
          <input id="admin-ban-days" type="number" min={1} max={365} value={days} onChange={(event) => setDays(event.target.value)} />
          <label htmlFor="admin-ban-reason">사유 (사용자에게 표시됨)</label>
          <div className="account-input-row">
            <input id="admin-ban-reason" value={reason} maxLength={200} onChange={(event) => setReason(event.target.value)} />
            <button type="button" className="account-button is-danger" disabled={busy || user.role === "ADMIN"} onClick={ban}>
              {user.tradeBannedUntil ? "다시 설정" : "거래 제한"}
            </button>
          </div>
        </section>

        <section className="account-form" aria-labelledby="admin-nick-title">
          <h4 id="admin-nick-title" className="admin-action-title">닉네임 강제 변경</h4>
          <p className="account-hint">현재: {user.nickname} · 비워 두면 "슈엔단0000" 형식으로 자동 생성합니다. 3일 변경 제한을 무시합니다.</p>
          <label htmlFor="admin-nickname">새 닉네임</label>
          <div className="account-input-row">
            <input id="admin-nickname" value={nickname} maxLength={10} placeholder="자동 생성" onChange={(event) => setNickname(event.target.value)} />
            <button type="button" className="account-button is-primary" disabled={busy} onClick={rename}>변경</button>
          </div>
        </section>
      </div>
    </Panel>
  );
}
