import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Pressable, StyleSheet, Switch, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { request, useAuth } from '../../auth';
import { AccountFrame, Button, Card, CardHead, Chips, Empty, Field, FieldError, Notice, Row, Segment, SideTag, kit, kstShort } from '../../components/Kit';
import { price, won } from '../../format';
import { SPACE, Txt, useTheme } from '../../theme';

/*
 * 관리자 화면. 서버가 모든 요청에서 관리자 권한을 다시 확인한다. 각 구획의 요청과 확인 단계는
 * 웹 관리 화면(src/pages/AdminPage.tsx, src/pages/admin/*)과 같다. 시장 운영 설정은 웹에만 둔다.
 */
const SECTIONS = [
  { key: 'users', label: '회원' },
  { key: 'orders', label: '주문' },
  { key: 'notices', label: '공지' },
  { key: 'promos', label: '프로모' },
  { key: 'audit', label: '감사 기록' },
];
const PAGE_SIZE = 50;
const ORDER_STATUS = { OPEN: '미체결', PARTIAL: '부분 체결', FILLED: '체결 완료', CANCELLED: '취소' };
const BAN_PRESETS = [1, 3, 7, 30];
const ACTIONS = {
  TRADE_BAN: '이용 정지', TRADE_BAN_LIFT: '이용 정지 해제', NICKNAME_FORCE: '닉네임 변경', ORDER_CANCEL: '주문 취소',
  SETTLEMENT_SETTLE: '정산 완료 처리', SETTLEMENT_REVERSE: '체결 취소', RUNTIME: '시장 운영 설정', STOCK_CREATE: '종목 등록',
  STOCK_UPDATE: '종목 정보 수정', STOCK_STATE: '종목 정지·재개', CASH_ADJUST: '현금 조정', CASH_BULK: '전체 보상',
  MISSION_RECOVERY: '미션 보상 복구', HOLDING_RESTORE: '보유 주식 복구', PERMISSIONS: '권한 변경', CONTENT_SAVE: '콘텐츠 저장',
  CONTENT_DELETE: '콘텐츠 삭제', MISSION_CONFIG: '미션 설정', REPORT_RESOLVE: '신고 처리', COMMENT_DELETE: '댓글 삭제',
  SEASON_CREATE: '시즌 생성', SEASON_START: '시즌 시작', SEASON_CLOSE: '시즌 종료', SEASON_RESET: '시즌 초기화', SEASON_USER_RESET: '시즌 회원 초기화',
};
const actionLabel = action => ACTIONS[String(action)] ?? String(action ?? '');
const num = value => (typeof value === 'number' ? value : Number(value ?? 0));
const confirm = (title, body, actionText, onConfirm, destructive = true) => Alert.alert(title, body, [
  { text: '취소', style: 'cancel' },
  { text: actionText, style: destructive ? 'destructive' : 'default', onPress: onConfirm },
]);

/** id 내림차순 목록을 before 커서로 이어 받는다(웹 useCursorList와 같은 규칙). */
function useCursorList(path) {
  const [state, setState] = useState({ rows: [], loading: false, done: false, error: '' });
  const latest = useRef(0);
  const load = useCallback(async (params, append) => {
    const ticket = ++latest.current;
    setState(current => ({ ...current, loading: true, error: '', rows: append ? current.rows : [] }));
    const query = new URLSearchParams(params);
    query.set('limit', String(PAGE_SIZE));
    if (append?.before) query.set('before', String(append.before));
    try {
      const page = await request(`${path}?${query.toString()}`);
      if (ticket !== latest.current) return;
      setState(current => ({ rows: append ? [...current.rows, ...page] : page, loading: false, done: page.length < PAGE_SIZE, error: '' }));
    } catch (failure) {
      if (ticket !== latest.current) return;
      setState(current => ({ ...current, loading: false, error: failure.message || '목록을 불러오지 못했어요.' }));
    }
  }, [path]);
  const lastId = state.rows.length ? num(state.rows[state.rows.length - 1].id) : 0;
  return { ...state, load, lastId };
}

export function AdminScreen({ market, onBack }) {
  const { profile } = useAuth();
  const [section, setSection] = useState('users');
  const names = Object.fromEntries((market?.stocks ?? []).map(stock => [stock.code, stock.name]));

  if (!profile?.admin) {
    return (
      <AccountFrame title="관리자" onBack={onBack}>
        <Card><Empty text={profile ? '관리자만 볼 수 있는 화면이에요.' : '권한을 확인하고 있어요.'} loading={!profile} /></Card>
      </AccountFrame>
    );
  }

  return (
    <AccountFrame title="관리자" onBack={onBack}>
      <Txt variant="caption" tone="inkSoft" style={styles.lead}>모든 조치는 감사 기록에 남아요. 시장 운영 설정은 웹 관리자 화면에서 바꿔요.</Txt>
      {/* 구획이 다섯이라 한 줄을 나누면 폰 폭에서 칸이 글자보다 좁아진다. 가로로 넘기는 칩으로 둔다. */}
      <View style={styles.sections} accessibilityLabel="관리 구획"><Chips options={SECTIONS} value={section} onChange={setSection} /></View>
      {section === 'users' ? <Users /> : null}
      {section === 'orders' ? <Orders names={names} /> : null}
      {section === 'notices' ? <Notices /> : null}
      {section === 'promos' ? <Promos /> : null}
      {section === 'audit' ? <Audit /> : null}
    </AccountFrame>
  );
}

function Users() {
  const theme = useTheme();
  const [query, setQuery] = useState('');
  const [users, setUsers] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [openId, setOpenId] = useState(null);

  const search = useCallback(async text => {
    setLoading(true);
    setError('');
    try {
      setUsers(await request(`/api/admin/users${text.trim() ? `?q=${encodeURIComponent(text.trim())}` : ''}`));
    } catch (failure) {
      setError(failure.message || '회원 목록을 불러오지 못했어요.');
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => { void search(''); }, [search]);

  const replace = next => setUsers(list => (list ?? []).map(user => (user.id === next.id ? next : user)));

  return (
    <>
      <Card>
        <Field
          label="이메일·닉네임·UID·회원 번호"
          value={query}
          onChangeText={setQuery}
          placeholder="비워 두면 최근 가입 50명"
          autoCapitalize="none"
          autoCorrect={false}
          returnKeyType="search"
          onSubmitEditing={() => void search(query)}
        />
        <Button label="검색" kind="primary" busy={loading} onPress={() => void search(query)} />
      </Card>
      {error ? <Notice text={error} /> : null}
      <Card padded={false}>
        <CardHead padded title="회원" meta={users ? `${users.length}명` : null} />
        {users == null ? (error
          ? <Empty text="회원 목록을 불러오지 못했어요." action="다시 시도" onAction={() => void search(query)} />
          : <Empty loading text="회원을 불러오는 중이에요" />) : users.length === 0 ? <Empty text="검색 결과가 없어요." /> : users.map((user, index) => {
          const open = openId === user.id;
          return (
            <View key={user.id} style={index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }}>
              <Row onPress={() => setOpenId(open ? null : user.id)} accessibilityLabel={`${user.nickname}, 회원 번호 ${user.id}. ${open ? '닫기' : '관리'}`}>
                <View style={kit.grow}>
                  <View style={styles.inline}>
                    <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{user.nickname}</Txt>
                    {user.role === 'ADMIN' ? <Badge text="관리자" /> : null}
                    {user.tradeBannedUntil ? <Badge text="이용 정지" tone="ink" /> : null}
                  </View>
                  <Txt variant="caption" tone="inkSoft" numberOfLines={1} numeric>{`#${user.id} · ${user.email || '이메일 없음'}`}</Txt>
                </View>
                <View style={kit.right}>
                  <Txt variant="caption" numeric>{won(user.cash)}</Txt>
                  <Txt variant="micro" tone="inkSoft" numeric>{`가입 ${kstShort(user.createdAt)}`}</Txt>
                </View>
                <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={18} color={theme.inkSoft} />
              </Row>
              {open ? <UserActions user={user} onChange={replace} /> : null}
            </View>
          );
        })}
      </Card>
    </>
  );
}

function Badge({ text, tone = 'inkSoft' }) {
  const theme = useTheme();
  return (
    <View style={[styles.badge, { borderColor: tone === 'ink' ? theme.ink : theme.edge }]}>
      <Txt variant="micro" tone={tone}>{text}</Txt>
    </View>
  );
}

function UserActions({ user, onChange }) {
  const theme = useTheme();
  const [days, setDays] = useState('7');
  // 사유는 정지당한 사용자에게 그대로 보이므로 미리 채우지 않는다. 관리자가 직접 적어야 정지할 수 있다.
  const [reason, setReason] = useState('');
  const [nickname, setNickname] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const run = async (action, done) => {
    setBusy(true);
    setMessage('');
    try {
      onChange(await action());
      setMessage(done);
    } catch (failure) {
      setMessage(failure.message || '처리하지 못했어요.');
    } finally {
      setBusy(false);
    }
  };

  const ban = () => {
    const value = Number(days);
    if (!Number.isInteger(value) || value < 1 || value > 365) { setMessage('정지 기간은 1~365일로 입력해 주세요.'); return; }
    confirm(`${user.nickname} 계정을 ${value}일 동안 이용 정지할까요?`, '정지 중에는 로그인과 정지 안내만 볼 수 있고, 미체결 주문은 모두 취소돼요.', '이용 정지', () => void run(
      () => request(`/api/admin/users/${user.id}/trade-ban`, { method: 'PUT', body: { days: value, reason } }),
      `${value}일 이용 정지했어요.`,
    ));
  };
  const lift = () => confirm(`${user.nickname} 계정의 이용 정지를 풀까요?`, '', '정지 해제', () => void run(
    () => request(`/api/admin/users/${user.id}/trade-ban`, { method: 'DELETE' }),
    '이용 정지를 풀었어요.',
  ), false);
  const rename = () => {
    const value = nickname.trim();
    if (value && (value.length < 2 || value.length > 10)) { setMessage('닉네임은 2~10자로 입력해 주세요.'); return; }
    confirm(`"${user.nickname}" 닉네임을 바꿀까요? 새 닉네임: ${value || '자동 생성'}`, '3일 변경 제한을 무시하고 바로 바뀌어요.', '닉네임 변경', () => void run(async () => {
      const next = await request(`/api/admin/users/${user.id}/nickname`, { method: 'PUT', body: { nickname: value } });
      setNickname('');
      return next;
    }, '닉네임을 바꿨어요.'), false);
  };

  return (
    <View style={[styles.actions, { backgroundColor: theme.raised }]}>
      {message ? <Txt variant="callout" accessibilityLiveRegion="polite">{message}</Txt> : null}
      <Txt variant="caption" tone="inkSoft" numeric selectable>{`UID ${user.uid ?? '-'}`}</Txt>

      <Txt variant="headline">이용 정지</Txt>
      <Txt variant="caption" tone="inkSoft">
        {user.tradeBannedUntil
          ? `${kstShort(user.tradeBannedUntil)}까지 정지돼 있어요.${user.tradeBanReason ? ` 사유: ${user.tradeBanReason}` : ''}`
          : '정지 중에는 로그인과 정지 안내만 볼 수 있어요. 미체결 주문은 바로 취소돼요.'}
      </Txt>
      {user.tradeBannedUntil ? <Button label="정지 해제" small busy={busy} onPress={lift} style={styles.alignStart} /> : null}
      <Chips options={BAN_PRESETS.map(preset => ({ key: String(preset), label: `${preset}일` }))} value={days} onChange={setDays} />
      <Field label="기간(일)" numeric unit="일" keyboardType="number-pad" value={days} onChangeText={text => setDays(text.replace(/\D/g, ''))} />
      <Field label="사유 (사용자에게 보여요)" value={reason} onChangeText={setReason} maxLength={200} placeholder="사용자에게 보일 정지 사유" />
      <Button label={user.tradeBannedUntil ? '기간 다시 정하기' : '이용 정지'} kind="danger" busy={busy} disabled={user.role === 'ADMIN' || !reason.trim()} onPress={ban} />
      {user.role === 'ADMIN' ? <Txt variant="caption" tone="inkSoft">관리자 계정은 정지할 수 없어요.</Txt> : null}

      <Txt variant="headline" style={styles.sectionGap}>닉네임 강제 변경</Txt>
      <Txt variant="caption" tone="inkSoft">{`지금: ${user.nickname}. 비워 두면 "슈엔단0000" 형식으로 자동으로 만들어요.`}</Txt>
      <Field label="새 닉네임" value={nickname} onChangeText={setNickname} maxLength={10} placeholder="자동 생성" autoCorrect={false} />
      <Button label="닉네임 변경" kind="primary" busy={busy} onPress={rename} />
      <Txt variant="caption" tone="inkSoft">현금 조정과 미션 보상 복구는 웹 관리자 화면에서 해요.</Txt>
    </View>
  );
}

const ORDER_FILTERS = [{ key: '', label: '전체' }, ...Object.entries(ORDER_STATUS).map(([key, label]) => ({ key, label }))];

function Orders({ names }) {
  const [userId, setUserId] = useState('');
  const [stockCode, setStockCode] = useState('');
  const [status, setStatus] = useState('');
  const [applied, setApplied] = useState({ userId: '', stockCode: '', status: '' });
  const list = useCursorList('/api/admin/orders');
  const params = filters => Object.fromEntries(Object.entries(filters).filter(([, value]) => value));

  useEffect(() => { void list.load(params(applied), null); }, [applied]); // eslint-disable-line react-hooks/exhaustive-deps

  const apply = next => {
    setUserId(next.userId);
    setStockCode(next.stockCode);
    setStatus(next.status);
    setApplied(next);
  };
  const filtered = applied.userId || applied.stockCode || applied.status;

  return (
    <>
      <Card>
        <View style={styles.filters}>
          <Field label="회원 번호" style={kit.grow} numeric keyboardType="number-pad" value={userId} placeholder="전체" onChangeText={text => setUserId(text.replace(/\D/g, ''))} />
          <Field label="종목 코드" style={kit.grow} value={stockCode} placeholder="전체" autoCapitalize="characters" autoCorrect={false} onChangeText={text => setStockCode(text.toUpperCase().replace(/[^A-Z]/g, ''))} />
        </View>
        <Chips options={ORDER_FILTERS} value={status} onChange={next => apply({ userId, stockCode, status: next })} />
        <View style={styles.filters}>
          <Button label="조회" kind="primary" style={kit.grow} busy={list.loading} onPress={() => apply({ userId, stockCode, status })} />
          {filtered ? <Button label="조건 지우기" style={kit.grow} onPress={() => apply({ userId: '', stockCode: '', status: '' })} /> : null}
        </View>
      </Card>
      {list.error ? <Notice text={list.error} /> : null}
      <Card padded={false}>
        <CardHead padded title="주문" meta={`${list.rows.length.toLocaleString('ko-KR')}건${list.done ? '' : ' 이상'}`} />
        {list.loading && list.rows.length === 0 ? <Empty loading text="주문을 불러오는 중이에요" />
          : list.error && list.rows.length === 0 ? <Empty text="주문을 불러오지 못했어요." action="다시 시도" onAction={() => void list.load(params(applied), null)} />
          : list.rows.length === 0 ? <Empty text={filtered ? '조건에 맞는 주문이 없어요.' : '아직 주문이 없어요.'} />
          : list.rows.map((row, index) => {
            const code = String(row.stock_code ?? '');
            const quantity = num(row.quantity);
            const filled = quantity - num(row.remaining_quantity);
            const buy = row.side === 'BUY';
            const who = String(row.user_id ?? '');
            return (
              <Row key={String(row.id)} divided={index > 0} onPress={() => apply({ ...applied, userId: who })} accessibilityLabel={`주문 ${row.id}, ${row.nickname} 회원. 누르면 이 회원의 주문만 봐요`}>
                <View style={kit.grow}>
                  <View style={styles.inline}>
                    <SideTag buy={buy} />
                    <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{names[code] ?? code}</Txt>
                    <Txt variant="caption" tone="inkSoft">{ORDER_STATUS[row.status] ?? row.status}</Txt>
                  </View>
                  <Txt variant="caption" tone="inkSoft" numeric numberOfLines={1}>{`${row.nickname ?? ''} #${who} · ${kstShort(row.created_at)}`}</Txt>
                </View>
                <View style={kit.right}>
                  <Txt variant="callout" numeric>{row.price ? `${price(num(row.price))}원` : '시장가'}</Txt>
                  <Txt variant="caption" tone="inkSoft" numeric>{`${filled.toLocaleString('ko-KR')}/${quantity.toLocaleString('ko-KR')}주`}</Txt>
                </View>
              </Row>
            );
          })}
        {!list.done && list.rows.length > 0 ? (
          <View style={styles.more}><Button label="다음 50건" busy={list.loading} onPress={() => void list.load(params(applied), { before: list.lastId })} /></View>
        ) : null}
      </Card>
    </>
  );
}

/* 공지 본문 끝의 "코드: …" 줄은 공지 창에서 복사 버튼이 붙는 코드다(웹 notice.ts와 같은 형식). */
const CODE_LINE = /\n\s*\n?코드:\s*(\S+)\s*$/;
const splitCode = body => {
  const match = String(body ?? '').match(CODE_LINE);
  return match ? { body: body.slice(0, match.index).trimEnd(), code: match[1] } : { body: String(body ?? ''), code: '' };
};
const joinCode = (body, code) => (code.trim() ? `${body.trim()}\n\n코드: ${code.trim()}` : body.trim());

/** 서버 시각 ↔ "2026-10-21 14:30"(한국시간). 비어 있으면 null. */
const toKstText = value => {
  if (!value) return '';
  const ms = typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(ms)) return '';
  const at = new Date(ms + 9 * 60 * 60_000);
  const pad = number => String(number).padStart(2, '0');
  return `${at.getUTCFullYear()}-${pad(at.getUTCMonth() + 1)}-${pad(at.getUTCDate())} ${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}`;
};
const KST_TEXT = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2})$/;
const toInstant = text => (text.trim() && KST_TEXT.test(text.trim()) ? new Date(`${text.trim().replace(' ', 'T')}:00+09:00`).toISOString() : null);
const badTime = text => Boolean(text.trim()) && (!KST_TEXT.test(text.trim()) || Number.isNaN(Date.parse(`${text.trim().replace(' ', 'T')}:00+09:00`)));

function noticeState(published, startsAt, endsAt, now = Date.now()) {
  if (!published) return '숨김';
  if (startsAt && Date.parse(startsAt) > now) return '예약';
  if (endsAt && Date.parse(endsAt) <= now) return '종료';
  return '게시 중';
}

const EMPTY_DRAFT = { title: '', body: '', code: '', image: '', actionLabel: '', actionUrl: '', dismissLabel: '', published: true, startsAt: '', endsAt: '' };
// 서버와 같은 규칙: 사이트 안 경로(/...)나 https 주소.
const LINK = /^(https:\/\/\S+|\/(?!\/)\S*)$/i;

function Notices() {
  const theme = useTheme();
  const [rows, setRows] = useState(null);
  const [error, setError] = useState('');
  const [editingId, setEditingId] = useState(null);
  const [draft, setDraft] = useState(EMPTY_DRAFT);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const load = useCallback(async () => {
    try {
      setRows(await request('/api/admin/content?kind=NOTICE&limit=100'));
      setError('');
    } catch (failure) {
      setError(failure.message || '공지를 불러오지 못했어요.');
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const set = (key, value) => setDraft(current => ({ ...current, [key]: value }));
  const startNew = () => { setEditingId(null); setDraft(EMPTY_DRAFT); setReason(''); setMessage(''); };
  const edit = row => {
    const { body, code } = splitCode(row.body);
    setEditingId(num(row.id));
    setDraft({
      title: String(row.title ?? ''), body, code, image: String(row.image_url ?? ''), actionLabel: String(row.action_label ?? ''),
      actionUrl: String(row.action_url ?? ''), dismissLabel: String(row.dismiss_label ?? ''),
      published: Boolean(row.published), startsAt: toKstText(row.starts_at), endsAt: toKstText(row.ends_at),
    });
    setReason('');
    setMessage('');
  };

  const timeError = badTime(draft.startsAt) || badTime(draft.endsAt) ? '시각은 2026-10-21 14:30 형식(한국시간)으로 적어 주세요.'
    : draft.startsAt && draft.endsAt && toInstant(draft.endsAt) <= toInstant(draft.startsAt) ? '끝나는 시각은 시작 시각보다 뒤여야 해요.' : '';
  const image = draft.image.trim(), actionText = draft.actionLabel.trim(), actionUrl = draft.actionUrl.trim();
  const linkError = (image && !LINK.test(image)) || (actionUrl && !LINK.test(actionUrl))
    ? '그림·이동 주소는 /로 시작하는 경로나 https:// 주소여야 해요.'
    : Boolean(actionText) !== Boolean(actionUrl) ? '이동 버튼은 문구와 주소를 함께 적어 주세요.' : '';
  const valid = draft.title.trim() && draft.body.trim() && reason.trim() && !timeError && !linkError;

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setMessage('');
    try {
      const saved = await request(editingId ? `/api/admin/content/${editingId}` : '/api/admin/content', {
        method: editingId ? 'PUT' : 'POST',
        body: {
          kind: 'NOTICE', title: draft.title.trim(), body: joinCode(draft.body, draft.code), stockCode: null,
          imageUrl: image || null, actionLabel: actionText || null, actionUrl: actionUrl || null, dismissLabel: draft.dismissLabel.trim() || null,
          published: draft.published, startsAt: toInstant(draft.startsAt), endsAt: toInstant(draft.endsAt), reason: reason.trim(),
        },
      });
      setMessage(editingId ? '공지를 고쳤어요. 이미 확인한 사용자에게도 다시 보여요.' : '공지를 등록했어요.');
      setEditingId(num(saved.id));
      setReason('');
      await load();
    } catch (failure) {
      setMessage(failure.message || '공지를 저장하지 못했어요.');
    } finally {
      setBusy(false);
    }
  };

  const remove = () => {
    if (!editingId || !reason.trim()) return;
    confirm(`"${draft.title}" 공지를 삭제할까요?`, '삭제한 내용은 감사 기록에만 남아요.', '삭제', async () => {
      setBusy(true);
      setMessage('');
      try {
        await request(`/api/admin/content/${editingId}`, { method: 'DELETE', body: { reason: reason.trim() } });
        startNew();
        setMessage('공지를 삭제했어요.');
        await load();
      } catch (failure) {
        setMessage(failure.message || '공지를 삭제하지 못했어요.');
      } finally {
        setBusy(false);
      }
    });
  };

  return (
    <>
      {error ? <Notice text={error} /> : null}
      <Card padded={false}>
        <View style={[kit.between, styles.headRow]}>
          <Txt variant="headline">공지</Txt>
          <Button label="새 공지" small icon="add" onPress={startNew} />
        </View>
        {rows == null ? (error
          ? <Empty text="공지를 불러오지 못했어요." action="다시 시도" onAction={() => void load()} />
          : <Empty loading text="공지를 불러오는 중이에요" />) : rows.length === 0 ? <Empty text="아직 공지가 없어요." /> : rows.map((row, index) => {
          const state = noticeState(Boolean(row.published), row.starts_at, row.ends_at);
          const selected = editingId === num(row.id);
          return (
            <Row key={String(row.id)} divided={index > 0} onPress={() => edit(row)} accessibilityLabel={`${row.title}, ${state}. 누르면 고쳐요`} style={selected && { backgroundColor: theme.raised }}>
              <View style={kit.grow}>
                <Txt variant="callout" numberOfLines={1}>{String(row.title ?? '')}</Txt>
                <Txt variant="caption" tone="inkSoft" numeric>{`${state} · ${kstShort(row.updated_at ?? row.created_at)}`}</Txt>
              </View>
              {selected ? <Txt variant="caption" tone="brandText">고치는 중</Txt> : <Ionicons name="create-outline" size={18} color={theme.inkSoft} />}
            </Row>
          );
        })}
      </Card>

      <Card>
        <CardHead title={editingId ? '공지 고치기' : '새 공지'} meta={noticeState(draft.published, toInstant(draft.startsAt), toInstant(draft.endsAt))} />
        <Field label="제목" value={draft.title} onChangeText={value => set('title', value)} maxLength={120} />
        <Field label="본문" value={draft.body} onChangeText={value => set('body', value)} multiline />
        <Field label="복사용 코드 (선택)" value={draft.code} onChangeText={value => set('code', value)} autoCapitalize="none" autoCorrect={false} hint="공지 창에서 복사 버튼이 붙어요." />
        <Field label="머리 그림 주소 (선택)" value={draft.image} onChangeText={value => set('image', value)} maxLength={500} autoCapitalize="none" autoCorrect={false} placeholder="/notice/season-2.png" hint="창 위쪽에 16:7 비율로 잘려 보여요(권장 960×420). 비우면 글만 보여요." />
        <Field label="이동 버튼 문구 (선택)" value={draft.actionLabel} onChangeText={value => set('actionLabel', value)} maxLength={40} placeholder="지금 확인하기" />
        <Field label="이동 주소" value={draft.actionUrl} onChangeText={value => set('actionUrl', value)} maxLength={500} autoCapitalize="none" autoCorrect={false} placeholder="/market 또는 https://" />
        <Field label="닫기 버튼 문구 (선택)" value={draft.dismissLabel} onChangeText={value => set('dismissLabel', value)} maxLength={40} placeholder={actionText ? '닫기' : '확인했어요'} hint="이동 버튼이 없으면 닫기 버튼 하나만 진하게 보여요." />
        {linkError ? <FieldError text={linkError} /> : null}
        <View style={kit.between}>
          <Txt variant="callout">게시</Txt>
          <Switch value={draft.published} onValueChange={value => set('published', value)} trackColor={{ true: theme.ink, false: theme.edge }} thumbColor={theme.card} accessibilityLabel="게시" />
        </View>
        <View style={styles.filters}>
          <Field label="시작 (선택)" style={kit.grow} value={draft.startsAt} onChangeText={value => set('startsAt', value)} placeholder="2026-10-21 14:30" autoCorrect={false} numeric={false} />
          <Field label="끝 (선택)" style={kit.grow} value={draft.endsAt} onChangeText={value => set('endsAt', value)} placeholder="비워 두면 계속" autoCorrect={false} />
        </View>
        {timeError ? <FieldError text={timeError} /> : <Txt variant="caption" tone="inkSoft">시각은 한국시간이에요.</Txt>}
        <Field label="사유 (감사 기록에 남아요)" value={reason} onChangeText={setReason} maxLength={300} />
        {message ? <Notice text={message} /> : null}
        <Button label={editingId ? '고친 내용 저장' : '공지 등록'} kind="primary" busy={busy} disabled={!valid} onPress={() => void save()} />
        {editingId ? <Button label="공지 삭제" kind="danger" disabled={busy || !reason.trim()} onPress={remove} /> : null}
      </Card>
    </>
  );
}

const PROMO_EMPTY = { code: '', rewardCash: '', titleIds: [], maxUses: '', startsAt: '', endsAt: '', active: true, memo: '' };
// 특별 칭호가 프로모션 코드의 본래 용도라 맨 앞에 둔다(웹 AdminPromos와 같은 순서).
const PROMO_CATEGORIES = ['특별', '자산', '수익률', '보유', '거래', '활동', '수집'];
const MAX_REWARD_CASH = 100_000_000;
const onlyDigits = value => value.replace(/[^0-9]/g, '');
const splitTitles = value => String(value ?? '').split(',').map(id => id.trim()).filter(Boolean);

function promoState(row, now = Date.now()) {
  if (!row.active) return '꺼짐';
  if (row.starts_at && Date.parse(row.starts_at) > now) return '예약';
  if (row.ends_at && Date.parse(row.ends_at) <= now) return '기간 끝';
  if (row.max_uses != null && num(row.used_count) >= num(row.max_uses)) return '소진';
  return '사용 가능';
}

const promoDraft = row => ({
  code: String(row.code ?? ''),
  rewardCash: num(row.reward_cash) ? String(num(row.reward_cash)) : '',
  titleIds: splitTitles(row.title_ids),
  maxUses: row.max_uses == null ? '' : String(row.max_uses),
  startsAt: toKstText(row.starts_at),
  endsAt: toKstText(row.ends_at),
  active: Boolean(row.active),
  memo: String(row.memo ?? ''),
});

const rewardSummary = (cash, titleIds, names) => {
  const parts = [];
  if (cash > 0) parts.push(`현금 ${won(cash)}`);
  if (titleIds.length) parts.push(`칭호 ${titleIds.map(id => names.get(id) ?? id).join(', ')}`);
  return parts.length ? parts.join(' + ') : '보상 없음';
};

/** 프로모션 코드 관리. 한 계정은 코드 하나를 한 번만 쓸 수 있고, 현금 보상은 랭킹 수익률에서 빠진다. */
function Promos() {
  const theme = useTheme();
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(null);
  const [draft, setDraft] = useState(PROMO_EMPTY);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [usage, setUsage] = useState(null);

  const load = useCallback(async () => {
    try {
      setData(await request('/api/admin/promos'));
      setError('');
    } catch (failure) {
      setError(failure.message || '프로모션 코드를 불러오지 못했어요.');
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    setUsage(null);
    if (!editing) return undefined;
    let alive = true;
    request(`/api/admin/promos/${encodeURIComponent(editing)}/redemptions`)
      .then(rows => { if (alive) setUsage(rows); })
      .catch(() => { if (alive) setUsage([]); });
    return () => { alive = false; };
  }, [editing, data]);

  const rows = data?.codes ?? null;
  const catalog = data?.titles ?? [];
  const names = new Map(catalog.map(title => [title.id, title.name]));
  const groups = PROMO_CATEGORIES.map(category => ({ category, titles: catalog.filter(title => title.category === category) })).filter(group => group.titles.length);

  const set = (key, value) => setDraft(current => ({ ...current, [key]: value }));
  const startNew = () => { setEditing(null); setDraft(PROMO_EMPTY); setReason(''); setMessage(''); };
  const edit = row => { setEditing(String(row.code)); setDraft(promoDraft(row)); setReason(''); setMessage(''); };
  const toggleTitle = id => set('titleIds', draft.titleIds.includes(id) ? draft.titleIds.filter(item => item !== id) : [...draft.titleIds, id]);

  const cash = Number(draft.rewardCash || 0);
  const codeError = !editing && draft.code && !/^[A-Za-z0-9_-]{2,40}$/.test(draft.code) ? '코드는 영문·숫자와 _ - 로 2~40자예요.' : '';
  const cashError = cash > MAX_REWARD_CASH ? '현금 보상은 1억원까지예요.' : '';
  const usesError = draft.maxUses !== '' && (Number(draft.maxUses) < 1 || Number(draft.maxUses) > 1_000_000) ? '인원 제한은 1~1,000,000명이에요.' : '';
  const timeError = badTime(draft.startsAt) || badTime(draft.endsAt) ? '시각은 2026-10-21 14:30 형식(한국시간)으로 적어 주세요.'
    : draft.startsAt && draft.endsAt && toInstant(draft.endsAt) <= toInstant(draft.startsAt) ? '끝나는 시각은 시작 시각보다 뒤여야 해요.' : '';
  const rewardError = cash === 0 && draft.titleIds.length === 0 ? '현금이나 칭호 중 하나는 골라 주세요.' : '';
  const valid = draft.code.trim() && reason.trim() && !codeError && !cashError && !usesError && !timeError && !rewardError;

  const save = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setMessage('');
    try {
      const saved = await request(editing ? `/api/admin/promos/${encodeURIComponent(editing)}` : '/api/admin/promos', {
        method: editing ? 'PUT' : 'POST',
        body: {
          code: draft.code.trim(), rewardCash: cash, titleIds: draft.titleIds, maxUses: draft.maxUses === '' ? null : Number(draft.maxUses),
          startsAt: toInstant(draft.startsAt), endsAt: toInstant(draft.endsAt), active: draft.active, memo: draft.memo.trim(), reason: reason.trim(),
        },
      });
      setMessage(editing ? '코드를 고쳤어요. 이미 받은 사람의 보상은 그대로예요.' : `${String(saved.code)} 코드를 만들었어요.`);
      setEditing(String(saved.code));
      setDraft(promoDraft(saved));
      setReason('');
      await load();
    } catch (failure) {
      setMessage(failure.message || '코드를 저장하지 못했어요.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {error ? <Notice text={error} /> : null}
      <Card padded={false}>
        <View style={[kit.between, styles.headRow]}>
          <Txt variant="headline">프로모션 코드</Txt>
          <Button label="새 코드" small icon="add" onPress={startNew} />
        </View>
        {rows == null ? (error
          ? <Empty text="프로모션 코드를 불러오지 못했어요." action="다시 시도" onAction={() => void load()} />
          : <Empty loading text="프로모션 코드를 불러오는 중이에요" />) : rows.length === 0 ? <Empty text="아직 코드가 없어요. 첫 코드를 만들어 보세요." /> : rows.map((row, index) => {
          const code = String(row.code);
          const selected = editing === code;
          const used = `${num(row.used_count).toLocaleString('ko-KR')}${row.max_uses != null ? ` / ${num(row.max_uses).toLocaleString('ko-KR')}` : ''}명 사용`;
          return (
            <Row key={code} divided={index > 0} onPress={() => edit(row)} accessibilityLabel={`${code}, ${promoState(row)}, ${used}. 누르면 고쳐요`} style={selected && { backgroundColor: theme.raised }}>
              <View style={kit.grow}>
                <Txt variant="callout" numeric numberOfLines={1}>{code}</Txt>
                <Txt variant="caption" tone="inkSoft" numberOfLines={2}>{rewardSummary(num(row.reward_cash), splitTitles(row.title_ids), names)}</Txt>
                <Txt variant="caption" tone="inkSoft" numeric>{`${promoState(row)} · ${used}`}</Txt>
              </View>
              {selected ? <Txt variant="caption" tone="brandText">고치는 중</Txt> : <Ionicons name="create-outline" size={18} color={theme.inkSoft} />}
            </Row>
          );
        })}
      </Card>

      <Card>
        <CardHead title={editing ? `${editing} 고치기` : '새 코드'} meta={rewardSummary(cash, draft.titleIds, names)} />
        <Field
          label="코드" value={draft.code} onChangeText={value => set('code', value.replace(/\s/g, ''))} maxLength={40} editable={!editing}
          autoCapitalize="none" autoCorrect={false} placeholder="launch2026"
          hint={editing ? '이미 쓴 사람의 기록이 이 코드에 묶여 있어 코드는 바꿀 수 없어요.' : '대소문자는 구분하지 않아요.'} error={codeError}
        />
        <Field
          label="현금 보상" unit="원" numeric value={draft.rewardCash} onChangeText={value => set('rewardCash', onlyDigits(value).slice(0, 10))} placeholder="0"
          hint={`${cash > 0 ? `${won(cash)}을 줘요. ` : '비우면 현금은 주지 않아요. '}받은 현금은 랭킹 수익률에서 빠져요.`} error={cashError}
        />
        <Txt variant="callout">{`칭호 보상 · ${draft.titleIds.length ? `${draft.titleIds.length}개 선택` : '선택 안 함'}`}</Txt>
        {groups.map(group => (
          <View key={group.category} style={styles.titleGroup}>
            <Txt variant="caption" tone="inkSoft">{group.category}</Txt>
            <View style={styles.titleOptions}>
              {group.titles.map(title => {
                const picked = draft.titleIds.includes(title.id);
                return (
                  <Pressable
                    key={title.id}
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: picked }}
                    onPress={() => toggleTitle(title.id)}
                    style={[styles.titleOption, { borderColor: picked ? theme.ink : theme.edge, backgroundColor: picked ? theme.ink : 'transparent' }]}
                  >
                    <Txt variant="caption" style={{ color: picked ? theme.ground : theme.ink }}>{title.name}</Txt>
                  </Pressable>
                );
              })}
            </View>
          </View>
        ))}
        {rewardError && draft.code ? <FieldError text={rewardError} /> : null}
        <Field label="사용 인원 제한" unit="명" numeric value={draft.maxUses} onChangeText={value => set('maxUses', onlyDigits(value).slice(0, 7))} placeholder="비우면 제한 없음" error={usesError} />
        <View style={styles.filters}>
          <Field label="시작 (선택)" style={kit.grow} value={draft.startsAt} onChangeText={value => set('startsAt', value)} placeholder="비우면 바로" autoCorrect={false} />
          <Field label="끝 (선택)" style={kit.grow} value={draft.endsAt} onChangeText={value => set('endsAt', value)} placeholder="비우면 계속" autoCorrect={false} />
        </View>
        {timeError ? <FieldError text={timeError} /> : <Txt variant="caption" tone="inkSoft">시각은 2026-10-21 14:30 형식의 한국시간이에요.</Txt>}
        <View style={kit.between}>
          <Txt variant="callout">코드 켜기</Txt>
          <Switch value={draft.active} onValueChange={value => set('active', value)} trackColor={{ true: theme.ink, false: theme.edge }} thumbColor={theme.card} accessibilityLabel="코드 켜기" />
        </View>
        <Field label="메모 (관리자만 봐요)" value={draft.memo} onChangeText={value => set('memo', value)} maxLength={200} placeholder="10월 방송 시청자용" />
        <Field label="사유 (감사 기록에 남아요)" value={reason} onChangeText={setReason} maxLength={300} placeholder={editing ? '기간 연장' : '오픈 이벤트'} />
        {message ? <Notice text={message} /> : null}
        <Button label={editing ? '변경 저장' : '코드 만들기'} kind="primary" busy={busy} disabled={!valid} onPress={() => void save()} />
      </Card>

      {editing ? (
        <Card padded={false}>
          <CardHead title="최근 사용" padded />
          {usage == null ? <Empty loading text="사용 기록을 불러오는 중이에요" />
            : usage.length === 0 ? <Empty text="아직 쓴 사람이 없어요." />
            : usage.map((entry, index) => (
              <Row key={String(entry.user_id)} divided={index > 0}>
                <Txt variant="callout" style={kit.grow} numberOfLines={1}>{String(entry.nickname ?? '')}</Txt>
                <Txt variant="caption" tone="inkSoft" numeric>{kstShort(entry.redeemed_at)}</Txt>
              </Row>
            ))}
        </Card>
      ) : null}
    </>
  );
}

const pretty = value => {
  if (value === null || value === undefined || value === '') return '';
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value;
    if (parsed && typeof parsed === 'object' && Object.keys(parsed).length === 0) return '';
    return JSON.stringify(parsed, null, 2);
  } catch {
    return String(value);
  }
};

const ACTION_FILTERS = [{ key: '', label: '전체' }, ...Object.entries(ACTIONS).map(([key, label]) => ({ key, label }))];

function Audit() {
  const [source, setSource] = useState('audit');
  const [actorId, setActorId] = useState('');
  const [userId, setUserId] = useState('');
  const [action, setAction] = useState('');
  const [applied, setApplied] = useState({});
  const list = useCursorList(source === 'audit' ? '/api/admin/audit' : '/api/admin/audit/legacy');
  const params = () => Object.fromEntries(Object.entries(applied).filter(([, value]) => value));

  // source가 바뀌면 load도 새 경로로 바뀌므로 함께 다시 읽는다.
  useEffect(() => { void list.load(params(), null); }, [applied, list.load]); // eslint-disable-line react-hooks/exhaustive-deps

  const switchSource = next => {
    if (next === source) return;
    setSource(next);
    setActorId('');
    setUserId('');
    setAction('');
    setApplied({});
  };
  const filtered = Object.values(applied).some(Boolean);

  return (
    <>
      <Card>
        <Segment options={[{ key: 'audit', label: '운영 기록' }, { key: 'legacy', label: '이전 기록' }]} value={source} onChange={switchSource} label="기록 종류" />
        <Txt variant="caption" tone="inkSoft">{source === 'audit' ? '사유와 변경 전후 값이 함께 남은 기록이에요.' : '운영 API 이전에 남은 이용 정지·닉네임 변경 기록이에요.'}</Txt>
        {source === 'audit' ? (
          <>
            <Field label="관리자 번호" numeric keyboardType="number-pad" value={actorId} placeholder="전체" onChangeText={text => setActorId(text.replace(/\D/g, ''))} />
            <Chips options={ACTION_FILTERS} value={action} onChange={next => { setAction(next); setApplied({ actorId, action: next }); }} />
          </>
        ) : (
          <Field label="대상 회원 번호" numeric keyboardType="number-pad" value={userId} placeholder="전체" onChangeText={text => setUserId(text.replace(/\D/g, ''))} />
        )}
        <View style={styles.filters}>
          <Button label="조회" kind="primary" style={kit.grow} busy={list.loading} onPress={() => setApplied(source === 'audit' ? { actorId, action } : { userId })} />
          {filtered ? <Button label="조건 지우기" style={kit.grow} onPress={() => { setActorId(''); setUserId(''); setAction(''); setApplied({}); }} /> : null}
        </View>
      </Card>
      {list.error ? <Notice text={list.error} /> : null}
      <Card padded={false}>
        <CardHead padded title="기록" meta={`${list.rows.length.toLocaleString('ko-KR')}건${list.done ? '' : ' 이상'}`} />
        {list.loading && list.rows.length === 0 ? <Empty loading text="기록을 불러오는 중이에요" />
          : list.error && list.rows.length === 0 ? <Empty text="기록을 불러오지 못했어요." action="다시 시도" onAction={() => void list.load(params(), null)} />
          : list.rows.length === 0 ? <Empty text={filtered ? '조건에 맞는 기록이 없어요.' : '아직 남은 기록이 없어요.'} />
          : list.rows.map((row, index) => (source === 'audit'
            ? <AuditEntry key={String(row.id)} row={row} divided={index > 0} />
            : (
              <Row key={String(row.id)} divided={index > 0}>
                <View style={kit.grow}>
                  <Txt variant="callout">{actionLabel(row.action)}</Txt>
                  <Txt variant="caption" tone="inkSoft" numeric>{`관리자 #${row.admin_id} → 회원 #${row.target_user_id} · ${kstShort(row.created_at)}`}</Txt>
                  {row.detail ? <Txt variant="caption" tone="inkSoft">{String(row.detail)}</Txt> : null}
                </View>
              </Row>
            )))}
        {!list.done && list.rows.length > 0 ? (
          <View style={styles.more}><Button label="다음 50건" busy={list.loading} onPress={() => void list.load(params(), { before: list.lastId })} /></View>
        ) : null}
      </Card>
    </>
  );
}

function AuditEntry({ row, divided }) {
  const theme = useTheme();
  const [open, setOpen] = useState(false);
  const before = pretty(row.before_json);
  const after = pretty(row.after_json);
  const hasDiff = Boolean(before || after);
  return (
    <View style={divided && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }}>
      <Pressable
        accessibilityRole={hasDiff ? 'button' : undefined}
        accessibilityState={hasDiff ? { expanded: open } : undefined}
        disabled={!hasDiff}
        onPress={() => setOpen(value => !value)}
        style={({ pressed }) => [styles.audit, pressed && { backgroundColor: theme.pressed }]}
      >
        <View style={kit.between}>
          <Txt variant="callout" style={styles.shrink}>{actionLabel(row.action)}</Txt>
          {hasDiff ? <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={18} color={theme.inkSoft} /> : null}
        </View>
        <Txt variant="caption" tone="inkSoft" numeric>{`대상 ${row.target ?? '-'} · 관리자 #${row.admin_id} · ${kstShort(row.created_at)}`}</Txt>
        {row.reason ? <Txt variant="caption">{String(row.reason)}</Txt> : null}
      </Pressable>
      {open ? (
        <View style={[styles.diff, { backgroundColor: theme.raised }]}>
          <Txt variant="caption" tone="inkSoft">변경 전</Txt>
          <Txt variant="micro" numeric selectable>{before || '없음'}</Txt>
          <Txt variant="caption" tone="inkSoft" style={styles.sectionGap}>변경 후</Txt>
          <Txt variant="micro" numeric selectable>{after || '없음'}</Txt>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  lead: { paddingHorizontal: SPACE.lg + 2, marginTop: -SPACE.sm, marginBottom: SPACE.md },
  sections: { marginBottom: SPACE.md },
  inline: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  shrink: { flexShrink: 1 },
  badge: { borderRadius: 4, borderWidth: 1, paddingHorizontal: 5, paddingVertical: 0 },
  actions: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.lg, gap: SPACE.sm },
  alignStart: { alignSelf: 'flex-start' },
  sectionGap: { marginTop: SPACE.md },
  filters: { flexDirection: 'row', gap: SPACE.sm },
  more: { padding: SPACE.lg },
  headRow: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg, paddingBottom: SPACE.sm },
  audit: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md, gap: 2 },
  diff: { marginHorizontal: SPACE.lg + 2, marginBottom: SPACE.md, padding: SPACE.md, borderRadius: 8, gap: 2 },
  titleGroup: { gap: SPACE.xs },
  titleOptions: { flexDirection: 'row', flexWrap: 'wrap', gap: SPACE.xs },
  titleOption: { minHeight: 44, borderWidth: 1, borderRadius: 6, paddingHorizontal: SPACE.md, justifyContent: 'center' },
});
