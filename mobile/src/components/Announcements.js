import React, { useState } from 'react';
import { Alert, Image, Linking, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { WEB_APP_URL } from '../config';
import { kstLong } from '../notices';
import { NUMBER_FONT, RADIUS, SPACE, Txt, useTheme } from '../theme';

// 웹의 SuspensionNotice, SiteNoticeDialog, CircuitBreakerNotice를 옮긴 화면. 노랑은 서킷브레이커에만 쓴다.

/** 관리자가 이용을 정지한 계정은 정지가 풀릴 때까지 이 화면만 본다. 서버도 개인 기능을 모두 거절한다. */
export function SuspensionScreen({ until, reason, onSignOut }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [busy, setBusy] = useState(false);
  const signOut = async () => {
    setBusy(true);
    try { await onSignOut(); } catch { Alert.alert('로그아웃하지 못했어요', '잠시 뒤 다시 눌러 주세요.'); } finally { setBusy(false); }
  };
  return (
    <ScrollView
      style={{ backgroundColor: theme.ground }}
      contentContainerStyle={[styles.suspension, { paddingTop: insets.top + SPACE.xxl * 2, paddingBottom: insets.bottom + SPACE.xl }]}
    >
      <View accessibilityRole="alert" style={styles.stack}>
        <Ionicons name="lock-closed" size={28} color={theme.ink} />
        <Txt variant="page" accessibilityRole="header">이용이 정지된 계정이에요</Txt>
        <Txt variant="body" tone="inkSoft">
          <Txt variant="body" numeric>{kstLong(until)}</Txt>까지 슈엔증권의 모든 서비스를 쓸 수 없어요.
        </Txt>
      </View>
      {reason ? (
        <View style={[styles.reason, { backgroundColor: theme.card }]}>
          <Txt variant="caption" tone="inkSoft">정지 사유</Txt>
          <Txt variant="body">{reason}</Txt>
        </View>
      ) : null}
      <Txt variant="callout" tone="inkSoft">정지 기간이 끝나면 다시 로그인하지 않아도 바로 쓸 수 있어요.</Txt>
      <Pressable
        accessibilityRole="button"
        accessibilityState={{ disabled: busy, busy }}
        disabled={busy}
        onPress={() => void signOut()}
        style={({ pressed }) => [styles.button, styles.outline, styles.start, { borderColor: theme.edge, backgroundColor: pressed ? theme.pressed : 'transparent', opacity: busy ? 0.45 : 1 }]}
      >
        <Txt variant="body">로그아웃</Txt>
      </Pressable>
    </ScrollView>
  );
}

/** 가운데 카드를 띄우는 창. 안드로이드 뒤로 가기는 닫기와 같다. */
function CardModal({ visible, onClose, label, children }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose} statusBarTranslucent>
      <Pressable style={[StyleSheet.absoluteFill, { backgroundColor: theme.scrim }]} onPress={onClose} accessibilityLabel={`${label} 닫기`} />
      <View style={[styles.wrap, { paddingTop: insets.top + SPACE.lg, paddingBottom: insets.bottom + SPACE.lg }]} pointerEvents="box-none">
        <View accessibilityViewIsModal style={[styles.card, { backgroundColor: theme.card }]}>
          <ScrollView bounces={false}>{children}</ScrollView>
        </View>
      </View>
    </Modal>
  );
}

const absoluteUrl = url => { try { return new URL(url, WEB_APP_URL).toString(); } catch { return null; } };

/**
 * 관리자가 올린 공지 한 장. 그림, 프로모션 코드, 이동 버튼은 공지에 따라 있거나 없다.
 * 이동 버튼의 https 주소는 기본 브라우저로, /charge 같은 앱 안 주소는 그 화면으로 연다.
 */
export function SiteNoticeModal({ notice, onDismiss, onGo }) {
  const theme = useTheme();
  // 그림을 못 불러오면 빈 띠를 남기지 않고 글만 있는 공지로 보여 준다.
  const [imageFailed, setImageFailed] = useState(false);
  if (!notice) return null;
  // 열 수 없는 주소의 이동 버튼은 닫기만 하므로 아예 그리지 않는다.
  const action = notice.action && (/^https:\/\//i.test(notice.action.url) || notice.action.url.startsWith('/')) ? notice.action : null;
  const image = notice.image && !imageFailed ? absoluteUrl(notice.image) : null;
  const follow = () => {
    onDismiss();
    if (/^https:\/\//i.test(action.url)) void Linking.openURL(action.url).catch(() => {});
    else onGo(action.url);
  };
  return (
    <CardModal visible onClose={onDismiss} label="공지">
      {image ? <Image source={{ uri: image }} style={[styles.image, { backgroundColor: theme.raised }]} onError={() => setImageFailed(true)} accessibilityIgnoresInvertColors /> : null}
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="공지 닫기"
        onPress={onDismiss}
        hitSlop={8}
        style={[styles.close, image && styles.closeOnImage]}
      >
        <Ionicons name="close" size={22} color={image ? '#FFFFFF' : theme.inkSoft} />
      </Pressable>
      <View style={[styles.body, !image && styles.bodyTall]}>
        <Txt variant="title" accessibilityRole="header">{notice.heading}</Txt>
        {notice.body ? <Txt variant="body" tone="inkSoft">{notice.body}</Txt> : null}
        {notice.code ? (
          <View style={[styles.code, { backgroundColor: theme.raised }]}>
            <Txt variant="caption" tone="inkSoft">코드</Txt>
            <Txt variant="headline" selectable style={styles.codeText}>{notice.code}</Txt>
          </View>
        ) : null}
      </View>
      <View style={styles.actions}>
        {action ? (
          <Pressable accessibilityRole="button" onPress={follow} style={({ pressed }) => [styles.button, { backgroundColor: theme.ink, opacity: pressed ? 0.85 : 1 }]}>
            <Txt variant="body" style={{ color: theme.ground }}>{action.label}</Txt>
          </Pressable>
        ) : null}
        <Pressable
          accessibilityRole="button"
          onPress={onDismiss}
          style={({ pressed }) => [
            styles.button,
            action ? [styles.outline, { borderColor: theme.edge, backgroundColor: pressed ? theme.pressed : 'transparent' }] : { backgroundColor: theme.ink, opacity: pressed ? 0.85 : 1 },
          ]}
        >
          <Txt variant="body" style={{ color: action ? theme.ink : theme.ground }}>{notice.dismissLabel || (action ? '닫기' : '확인했어요')}</Txt>
        </Pressable>
      </View>
    </CardModal>
  );
}

const PHASE = { HALTED: '거래 일시 중단', AUCTION: '단일가 접수 중', CLOSED: '오늘 거래 종료' };
const NEXT = { HALTED: '단일가 접수 시작', AUCTION: '단일가 체결', CLOSED: '다음 거래일 시작' };

/** 시장 전체 서킷브레이커. 몇 단계인지, 지금 무엇이 막혔는지, 언제 풀리는지를 한 장에 보여 준다. */
export function CircuitBreakerModal({ restriction, onClose, onConfirm }) {
  const theme = useTheme();
  if (!restriction) return null;
  const ends = Date.parse(restriction.endsAt);
  return (
    <CardModal visible onClose={onClose} label="서킷브레이커 안내">
      <View style={[styles.guardBand, { backgroundColor: theme.guardWash, borderColor: theme.guard }]}>
        <Ionicons name="shield-half" size={20} color={theme.guardText} />
        <Txt variant="callout" style={{ color: theme.guardText }}>{PHASE[restriction.phase] ?? '거래 제한 중'}</Txt>
      </View>
      <View style={styles.body}>
        <Txt variant="title" accessibilityRole="header">서킷브레이커 {restriction.level}단계</Txt>
        {restriction.reason ? <Txt variant="body" tone="inkSoft">{restriction.reason}</Txt> : null}
        {restriction.effect ? <Txt variant="body">{restriction.effect}</Txt> : null}
        {Number.isFinite(ends) ? (
          <View style={[styles.when, { borderTopColor: theme.line }]}>
            <Txt variant="caption" tone="inkSoft">{NEXT[restriction.phase] ?? '제한 종료'} 예정</Txt>
            <Txt variant="callout" numeric>{kstLong(ends)} (한국시간)</Txt>
          </View>
        ) : null}
      </View>
      <View style={styles.actions}>
        <Pressable accessibilityRole="button" onPress={onConfirm} style={({ pressed }) => [styles.button, { backgroundColor: theme.ink, opacity: pressed ? 0.85 : 1 }]}>
          <Txt variant="body" style={{ color: theme.ground }}>{restriction.level === 3 ? '오늘 하루 보지 않기' : '확인했어요'}</Txt>
        </Pressable>
      </View>
    </CardModal>
  );
}

const styles = StyleSheet.create({
  suspension: { flexGrow: 1, paddingHorizontal: SPACE.xl, gap: SPACE.lg, maxWidth: 520, width: '100%', alignSelf: 'center' },
  stack: { gap: SPACE.md },
  reason: { borderRadius: RADIUS, padding: SPACE.lg, gap: SPACE.xs },
  start: { alignSelf: 'flex-start', marginTop: SPACE.sm },
  wrap: { flex: 1, justifyContent: 'center', paddingHorizontal: SPACE.xl },
  card: { width: '100%', maxWidth: 420, alignSelf: 'center', borderRadius: RADIUS, maxHeight: '100%', overflow: 'hidden' },
  image: { width: '100%', aspectRatio: 16 / 7 },
  close: { position: 'absolute', top: SPACE.sm, right: SPACE.sm, zIndex: 1, width: 44, height: 44, borderRadius: RADIUS, alignItems: 'center', justifyContent: 'center' },
  // 그림 위에서는 흰 X. 흰 그림 위에서도 4.7:1이 나오도록 그늘을 55%로 깐다.
  closeOnImage: { backgroundColor: 'rgba(0, 0, 0, 0.55)' },
  body: { paddingHorizontal: SPACE.xl, paddingTop: SPACE.xl, gap: SPACE.md },
  bodyTall: { paddingTop: SPACE.xxl + SPACE.md, paddingRight: SPACE.xxl + SPACE.lg },
  code: { flexDirection: 'row', alignItems: 'baseline', alignSelf: 'flex-start', gap: SPACE.sm, borderRadius: RADIUS, paddingHorizontal: SPACE.md, paddingVertical: SPACE.sm },
  codeText: { fontFamily: NUMBER_FONT, letterSpacing: 0.5 },
  actions: { padding: SPACE.xl, gap: SPACE.sm },
  button: { minHeight: 52, borderRadius: RADIUS, alignItems: 'center', justifyContent: 'center', paddingHorizontal: SPACE.lg },
  outline: { borderWidth: 1 },
  guardBand: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, paddingHorizontal: SPACE.xl, paddingVertical: SPACE.md, borderBottomWidth: 1 },
  when: { borderTopWidth: StyleSheet.hairlineWidth, paddingTop: SPACE.md, gap: 2 },
});
