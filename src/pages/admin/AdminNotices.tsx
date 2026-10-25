import { useCallback, useEffect, useMemo, useState } from "react";
import { Panel } from "../../components/Panel";
import { SiteNoticeContent } from "../../components/SiteNoticeDialog";
import { apiFetch } from "../../lib/api";
import { joinNoticeCode, noticeItem, splitNoticeCode } from "../../notice";
import { adminTime, num, toInstant, toLocalInput, type Row } from "./adminShared";

type Draft = { title: string; body: string; code: string; published: boolean; startsAt: string; endsAt: string };
type State = "live" | "scheduled" | "ended" | "hidden";

const EMPTY: Draft = { title: "", body: "", code: "", published: true, startsAt: "", endsAt: "" };
const STATE_LABEL: Record<State, string> = { live: "게시 중", scheduled: "예약", ended: "종료", hidden: "숨김" };


function stateOf(published: boolean, startsAt: unknown, endsAt: unknown, now = Date.now()): State {
  if (!published) return "hidden";
  if (startsAt && new Date(String(startsAt)).getTime() > now) return "scheduled";
  if (endsAt && new Date(String(endsAt)).getTime() <= now) return "ended";
  return "live";
}
const rowState = (row: Row) => stateOf(Boolean(row.published), row.starts_at, row.ends_at);

function draftFrom(row: Row): Draft {
  const { body, code } = splitNoticeCode(String(row.body ?? ""));
  return {
    title: String(row.title ?? ""),
    body,
    code,
    published: Boolean(row.published),
    startsAt: toLocalInput(row.starts_at),
    endsAt: toLocalInput(row.ends_at),
  };
}

/**
 * 첫 방문 공지 편집. 게시 중인 공지는 모두 한 창에 모여 사용자에게 한 번 보이고,
 * 하나라도 고치면 이미 확인한 사람에게도 다시 보인다. 오른쪽 미리보기는 실제
 * 공지 창과 같은 마크업이다.
 */
export function AdminNotices() {
  const [rows, setRows] = useState<Row[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setRows(await apiFetch<Row[]>("/api/admin/content?kind=NOTICE&limit=100"));
      setError("");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "공지를 불러오지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const startNew = () => { setEditingId(null); setDraft(EMPTY); setReason(""); setMessage(""); };
  const edit = (row: Row) => { setEditingId(num(row.id)); setDraft(draftFrom(row)); setReason(""); setMessage(""); };
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }));

  const draftState = stateOf(draft.published, toInstant(draft.startsAt), toInstant(draft.endsAt));
  const timeError = draft.startsAt && draft.endsAt && draft.endsAt <= draft.startsAt ? "끝나는 시각은 시작 시각보다 뒤여야 합니다." : "";
  const valid = draft.title.trim() && draft.body.trim() && reason.trim() && !timeError;

  // 사용자가 보게 될 창: 지금 게시 중인 다른 공지 + 편집 중인 공지(지금 보이는 상태라면). 최신 공지가 위.
  const preview = useMemo(() => {
    const others = rows.filter((row) => num(row.id) !== editingId && rowState(row) === "live")
      .map((row) => ({ id: num(row.id), item: noticeItem({ title: String(row.title), body: String(row.body) }) }));
    const mine = draftState === "live" && draft.title.trim()
      ? [{ id: editingId ?? Number.MAX_SAFE_INTEGER, item: { heading: draft.title.trim(), body: draft.body.trim(), ...(draft.code.trim() ? { code: draft.code.trim() } : {}) } }]
      : [];
    return [...mine, ...others].sort((a, b) => b.id - a.id).map((entry) => entry.item);
  }, [rows, editingId, draft, draftState]);

  const save = async () => {
    if (!valid) return;
    setBusy(true);
    setMessage("");
    try {
      const saved = await apiFetch<Row>(editingId ? `/api/admin/content/${editingId}` : "/api/admin/content", {
        method: editingId ? "PUT" : "POST",
        body: JSON.stringify({
          kind: "NOTICE",
          title: draft.title.trim(),
          body: joinNoticeCode(draft.body, draft.code),
          stockCode: null,
          published: draft.published,
          startsAt: toInstant(draft.startsAt),
          endsAt: toInstant(draft.endsAt),
          reason: reason.trim(),
        }),
      });
      setMessage(editingId ? "공지를 고쳤습니다. 이미 확인한 사용자에게도 다시 보입니다." : "공지를 등록했습니다.");
      setEditingId(num(saved.id));
      setReason("");
      await load();
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "공지를 저장하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!editingId || !reason.trim()) return;
    if (!window.confirm(`"${draft.title}" 공지를 삭제할까요?\n삭제한 내용은 감사 기록에만 남습니다.`)) return;
    setBusy(true);
    setMessage("");
    try {
      await apiFetch<void>(`/api/admin/content/${editingId}`, { method: "DELETE", body: JSON.stringify({ reason: reason.trim() }) });
      startNew();
      setMessage("공지를 삭제했습니다.");
      await load();
    } catch (failure) {
      setMessage(failure instanceof Error ? failure.message : "공지를 삭제하지 못했습니다.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel id="admin-notices" title="공지" meta={loading ? "불러오는 중" : `${rows.length}개`} level={2}>
      <div className="notice-admin">
        <div className="notice-editor">
          <div className="notice-list-head">
            <h3 className="member-title">공지 목록</h3>
            <button type="button" className="text-button" onClick={startNew} aria-pressed={editingId === null}>새 공지 쓰기</button>
          </div>
          {error && <p className="account-message" role="alert">{error}</p>}
          {rows.length > 0 ? (
            <ul className="notice-list">
              {rows.map((row) => {
                const state = rowState(row);
                const id = num(row.id);
                return (
                  <li key={id}>
                    <button type="button" className={`notice-list-item${editingId === id ? " is-selected" : ""}`} aria-current={editingId === id ? "true" : undefined} onClick={() => edit(row)}>
                      <span className={`notice-state is-${state}`}>{STATE_LABEL[state]}</span>
                      <span className="notice-list-title">{String(row.title)}</span>
                      <span className="notice-list-time num">{adminTime(row.updated_at ?? row.created_at)}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : !loading && !error && <p className="member-empty">아직 공지가 없습니다. 첫 공지를 써 보세요.</p>}

          <form className="account-form notice-form" onSubmit={(event) => { event.preventDefault(); void save(); }}>
            <h3 className="member-title">{editingId ? "공지 고치기" : "새 공지"}</h3>
            <label htmlFor="notice-title">제목</label>
            <input id="notice-title" value={draft.title} maxLength={150} placeholder="예: 프로모션 코드" onChange={(event) => set("title", event.target.value)} />
            <label htmlFor="notice-body">본문</label>
            <textarea id="notice-body" value={draft.body} rows={5} maxLength={19_900} placeholder="사용자에게 보일 내용을 적어 주세요. 줄바꿈은 그대로 보입니다."
              onChange={(event) => set("body", event.target.value)} />
            <label htmlFor="notice-code">코드 (선택)</label>
            <input id="notice-code" value={draft.code} maxLength={40} placeholder="예: syuen" autoCapitalize="none" spellCheck={false}
              onChange={(event) => set("code", event.target.value.replace(/\s/g, ""))} />
            <p className="account-hint">적으면 본문 아래에 복사하기 쉬운 코드 상자로 보입니다.</p>

            <label className="notice-check">
              <input type="checkbox" checked={draft.published} onChange={(event) => set("published", event.target.checked)} />
              사용자에게 보이기
            </label>
            <div className="admin-form-pair">
              <div className="account-field">
                <label htmlFor="notice-start">시작 (비우면 바로)</label>
                <input id="notice-start" type="datetime-local" value={draft.startsAt} onChange={(event) => set("startsAt", event.target.value)} />
              </div>
              <div className="account-field">
                <label htmlFor="notice-end">끝 (비우면 계속)</label>
                <input id="notice-end" type="datetime-local" value={draft.endsAt} min={draft.startsAt || undefined} onChange={(event) => set("endsAt", event.target.value)} />
              </div>
            </div>
            {timeError && <p className="admin-form-message" role="alert">{timeError}</p>}

            <label htmlFor="notice-reason">사유 (감사 기록에 남음)</label>
            <input id="notice-reason" value={reason} maxLength={300} placeholder={editingId ? "예: 문구 수정" : "예: 프로모션 안내"} onChange={(event) => setReason(event.target.value)} />
            <div className="notice-actions">
              <button type="submit" className="account-button is-primary" disabled={busy || !valid}>{editingId ? "변경 저장" : "공지 등록"}</button>
              {editingId && <button type="button" className="account-button is-danger" disabled={busy || !reason.trim()} onClick={() => void remove()}>삭제</button>}
            </div>
            {message && <p className="admin-form-message" role="status">{message}</p>}
          </form>
        </div>

        <aside className="notice-preview" aria-label="사용자에게 보이는 공지 창 미리보기">
          <p className="notice-preview-label">
            {draftState === "live" ? "사용자가 처음 들어오면 이렇게 보입니다." : `이 공지는 지금 ${STATE_LABEL[draftState]} 상태라 아래 창에 들어가지 않습니다.`}
          </p>
          <div className="notice-preview-stage">
            {preview.length ? (
              <div className="notice-preview-card site-notice">
                <SiteNoticeContent items={preview} titleId="notice-preview-title" />
              </div>
            ) : (
              <p className="notice-preview-empty">게시 중인 공지가 없어서 공지 창이 뜨지 않습니다.</p>
            )}
          </div>
        </aside>
      </div>
    </Panel>
  );
}
