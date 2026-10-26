import React, { useState } from 'react';
import { ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { FONT_FAMILY, NUMBER_FONT, RADIUS, RADIUS_SM, SPACE, TYPE, Txt, useTheme } from '../theme';

// 계정·파생 화면이 같이 쓰는 부품. 카드는 그림자 없는 8px 패널이고 카드 속 카드는 만들지 않는다.
// 주 버튼만 잉크 면이고, 색 면은 매수·매도와 같은 뜻일 때만 쓴다.

/** 위쪽 뒤로 가기 막대, 화면 제목, 아래로 당겨 새로고침이 있는 스크롤 화면. */
export function AccountFrame({ title, onBack, onRefresh, children, footer }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const [refreshing, setRefreshing] = useState(false);
  const refresh = onRefresh ? async () => {
    setRefreshing(true);
    try { await onRefresh(); } finally { setRefreshing(false); }
  } : undefined;
  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      <View style={[styles.bar, { paddingTop: insets.top, backgroundColor: theme.ground }]}>
        <Pressable accessibilityRole="button" accessibilityLabel="설정으로 돌아가기" onPress={onBack} style={styles.barButton}>
          <Ionicons name="chevron-back" size={26} color={theme.ink} />
        </Pressable>
      </View>
      {/* 아이폰에서 키보드가 올라오면 그 높이만큼 스크롤 여백을 늘려, 아래쪽 입력칸도 키보드 위로 올라오게 한다.
          Android는 Expo 기본 설정(화면 크기 조절)이 같은 일을 한다. */}
      <ScrollView
        automaticallyAdjustKeyboardInsets
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        contentContainerStyle={styles.content}
        refreshControl={refresh ? <RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={theme.inkSoft} colors={[theme.ink]} progressBackgroundColor={theme.raised} /> : undefined}
      >
        <Txt variant="page" accessibilityRole="header" style={styles.title}>{title}</Txt>
        {children}
      </ScrollView>
      {footer}
    </View>
  );
}

export function Card({ children, padded = true, style }) {
  const theme = useTheme();
  return <View style={[styles.card, { backgroundColor: theme.card }, padded && styles.cardPadded, style]}>{children}</View>;
}

/** 카드 머리. 오른쪽 meta는 개수나 기준 같은 짧은 보조 정보. */
export function CardHead({ title, meta, padded = false }) {
  return (
    <View style={[styles.head, padded && styles.headPadded]}>
      <Txt variant="headline" accessibilityRole="header">{title}</Txt>
      {meta ? <Txt variant="caption" tone="inkSoft" numeric>{meta}</Txt> : null}
    </View>
  );
}

/** 표 대신 쓰는 한 줄. 왼쪽 이름, 오른쪽 값. 행 사이는 1px 괘선. */
export function Row({ children, divided, onPress, accessibilityLabel, style }) {
  const theme = useTheme();
  const base = [styles.row, divided && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }, style];
  if (!onPress) return <View style={base} accessible={Boolean(accessibilityLabel)} accessibilityLabel={accessibilityLabel}>{children}</View>;
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={accessibilityLabel} onPress={onPress} style={({ pressed }) => [...base, pressed && { backgroundColor: theme.pressed }]}>
      {children}
    </Pressable>
  );
}

/** 이름과 값 한 쌍. 요약 숫자 묶음에 쓴다. */
export function Figure({ label, value, tone = 'ink' }) {
  return (
    <View style={styles.figure}>
      <Txt variant="caption" tone="inkSoft">{label}</Txt>
      <Txt variant="callout" tone={tone} numeric numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.8}>{value}</Txt>
    </View>
  );
}

/**
 * kind: primary(주 동작) · ghost · danger(되돌릴 수 없음) · buy / sell(매수·매도 방향의 돈 흐름).
 * 적색은 "상승"이라 위험 표시에 빌리지 않는다. danger는 잉크 테두리와 경고 아이콘이고, 늘 확인 창을 거친다.
 */
export function Button({ label, onPress, kind = 'ghost', disabled, busy, small, icon, style, accessibilityLabel }) {
  const theme = useTheme();
  const surface = {
    primary: { backgroundColor: theme.ink, borderColor: theme.ink, color: theme.ground },
    ghost: { backgroundColor: 'transparent', borderColor: theme.edge, color: theme.ink },
    danger: { backgroundColor: 'transparent', borderColor: theme.ink, color: theme.ink },
    buy: { backgroundColor: theme.upFill, borderColor: theme.upFill, color: theme.onFill },
    sell: { backgroundColor: theme.downFill, borderColor: theme.downFill, color: theme.onFill },
  }[kind];
  const off = disabled || busy;
  const glyph = icon ?? (kind === 'danger' ? 'alert-circle-outline' : null);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled: Boolean(off), busy: Boolean(busy) }}
      disabled={off}
      onPress={onPress}
      style={({ pressed }) => [
        small ? styles.buttonSmall : styles.button,
        { backgroundColor: surface.backgroundColor, borderColor: surface.borderColor },
        pressed && { opacity: 0.85 },
        off && { opacity: 0.45 },
        style,
      ]}
    >
      {busy ? <ActivityIndicator color={surface.color} /> : glyph ? <Ionicons name={glyph} size={small ? 16 : 18} color={surface.color} /> : null}
      <Txt variant={small ? 'caption' : 'body'} style={{ color: surface.color }}>{label}</Txt>
    </Pressable>
  );
}

/** 한 줄 입력칸. numeric이면 고정폭 숫자로 쓰고 오른쪽에 단위를 붙인다. */
export function Field({ label, hint, error, unit, numeric, multiline, style, ...props }) {
  const theme = useTheme();
  return (
    <View style={[styles.field, style]}>
      {label ? <Txt variant="caption" tone="inkSoft">{label}</Txt> : null}
      <View style={[styles.input, multiline && styles.inputMulti, { backgroundColor: theme.raised, borderColor: error ? theme.ink : theme.edge }, error && styles.inputError]}>
        <TextInput
          placeholderTextColor={theme.inkSoft}
          keyboardAppearance={theme.scheme}
          selectionColor={theme.brand}
          multiline={multiline}
          accessibilityLabel={label}
          {...props}
          style={[
            styles.inputText,
            { color: theme.ink, fontFamily: numeric ? NUMBER_FONT : FONT_FAMILY },
            numeric && { textAlign: 'right', fontVariant: ['tabular-nums'] },
            multiline && { minHeight: 120, textAlignVertical: 'top', paddingTop: SPACE.md },
          ]}
        />
        {unit ? <Txt variant="callout" tone="inkSoft">{unit}</Txt> : null}
      </View>
      {error ? <FieldError text={error} /> : hint ? <Txt variant="caption" tone="inkSoft">{hint}</Txt> : null}
    </View>
  );
}

/** 입력 오류 문구. 적색(상승) 대신 잉크 글자와 경고 아이콘으로 표시한다. */
export function FieldError({ text }) {
  const theme = useTheme();
  return (
    <View style={styles.fieldError} accessibilityLiveRegion="polite">
      <Ionicons name="alert-circle" size={14} color={theme.ink} />
      <Txt variant="caption" style={styles.fill}>{text}</Txt>
    </View>
  );
}

/**
 * 짧은 결과·오류 안내. guard는 거래 보호·대출 경고처럼 노랑이 뜻을 가질 때만.
 * 라이트에서는 돋움색 면이 바탕과 거의 같아(1.04:1) 경계선으로 상자를 드러낸다.
 */
export function Notice({ text, tone = 'neutral', icon, children }) {
  const theme = useTheme();
  const guard = tone === 'guard';
  const color = guard ? theme.guardText : theme.ink;
  return (
    <View style={[styles.notice, { backgroundColor: guard ? theme.guardWash : theme.raised, borderColor: guard ? theme.guard : theme.edge }]} accessibilityLiveRegion="polite">
      <Ionicons name={icon ?? (guard ? 'shield-half' : tone === 'ok' ? 'checkmark-circle' : 'alert-circle')} size={18} color={tone === 'ok' ? theme.inkSoft : color} />
      <View style={styles.fill}>
        {text ? <Txt variant="callout" style={{ color }}>{text}</Txt> : null}
        {children}
      </View>
    </View>
  );
}

/** 둘에서 넷 사이의 선택지. 선택된 칸은 잉크 면. */
export function Segment({ options, value, onChange, label }) {
  const theme = useTheme();
  return (
    <View style={[styles.segment, { backgroundColor: theme.raised }]} accessibilityRole="tablist" accessibilityLabel={label}>
      {options.map(option => {
        const selected = option.key === value;
        return (
          <Pressable
            key={option.key}
            accessibilityRole="tab"
            accessibilityState={{ selected, disabled: option.disabled }}
            disabled={option.disabled}
            onPress={() => onChange(option.key)}
            style={[styles.segmentItem, selected && { backgroundColor: theme.ink }, option.disabled && { opacity: 0.4 }]}
          >
            <Txt variant="caption" style={{ color: selected ? theme.ground : theme.ink }}>{option.label}</Txt>
          </Pressable>
        );
      })}
    </View>
  );
}

/** 가로로 넘기는 칩 줄. 분류가 많을 때 쓴다. count는 칩 옆의 작은 숫자. */
export function Chips({ options, value, onChange }) {
  const theme = useTheme();
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips} accessibilityRole="tablist">
      {options.map(option => {
        const selected = option.key === value;
        return (
          <Pressable
            key={option.key}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            onPress={() => onChange(option.key)}
            style={[styles.chip, { backgroundColor: selected ? theme.ink : theme.card, borderColor: selected ? theme.ink : theme.edge }]}
          >
            <Txt variant="caption" style={{ color: selected ? theme.ground : theme.ink }}>{option.label}</Txt>
            {option.count ? <Txt variant="micro" numeric style={{ color: selected ? theme.ground : theme.inkSoft }}>{option.count}</Txt> : null}
          </Pressable>
        );
      })}
    </ScrollView>
  );
}

/** 불러오는 중·비어 있음·실패 상태의 한 줄. */
export function Empty({ text, loading, action, onAction }) {
  const theme = useTheme();
  return (
    <View style={styles.empty} accessibilityLiveRegion="polite">
      {loading ? <ActivityIndicator color={theme.inkSoft} /> : null}
      <Txt variant="callout" tone="inkSoft" style={styles.emptyText}>{text}</Txt>
      {action ? <Button label={action} onPress={onAction} small /> : null}
    </View>
  );
}

/** 매수 적·매도 청 작은 표식. 체결·미체결 줄의 구분에 쓴다. */
export function SideTag({ buy, label }) {
  const theme = useTheme();
  return (
    <View style={[styles.tag, { backgroundColor: buy ? theme.upWash : theme.downWash }]}>
      <Txt variant="micro" tone={buy ? 'up' : 'down'}>{label ?? (buy ? '매수' : '매도')}</Txt>
    </View>
  );
}

/**
 * 화면을 옮긴 뒤에도 보여야 하는 짧은 결과 알림(미션 보상 지급 등). 탭바 바로 위에 잉크 면으로 3초 동안 뜬다.
 * 움직임은 쓰지 않는다(MOTION 1). 스크린리더에는 바로 읽힌다.
 */
export function Toast({ text, icon = 'checkmark-circle' }) {
  const theme = useTheme();
  if (!text) return null;
  return (
    <View pointerEvents="none" style={styles.toastWrap}>
      <View style={[styles.toast, { backgroundColor: theme.ink }]} accessibilityLiveRegion="assertive" accessibilityRole="alert">
        <Ionicons name={icon} size={18} color={theme.ground} />
        <Txt variant="callout" style={{ color: theme.ground, flexShrink: 1 }}>{text}</Txt>
      </View>
    </View>
  );
}

/** 한국시간 "10.21 14:32". 기기 시간대와 상관없이 같은 값을 보여 준다. */
export function kstShort(value) {
  const ms = typeof value === 'number' ? value : Date.parse(value);
  if (!Number.isFinite(ms)) return '-';
  const at = new Date(ms + 9 * 60 * 60_000);
  const pad = number => String(number).padStart(2, '0');
  return `${at.getUTCMonth() + 1}.${at.getUTCDate()} ${pad(at.getUTCHours())}:${pad(at.getUTCMinutes())}`;
}

export const kit = StyleSheet.create({
  stack: { gap: SPACE.md },
  between: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: SPACE.md },
  grow: { flex: 1, minWidth: 0 },
  right: { alignItems: 'flex-end' },
  hero: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.xl, gap: 2 },
});

const styles = StyleSheet.create({
  fill: { flex: 1 },
  bar: { flexDirection: 'row', paddingHorizontal: SPACE.xs },
  barButton: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  content: { paddingHorizontal: SPACE.lg, paddingBottom: SPACE.xxl },
  title: { paddingHorizontal: SPACE.lg + 2, marginBottom: SPACE.lg },
  card: { borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  cardPadded: { padding: SPACE.lg + 2, gap: SPACE.md },
  head: { flexDirection: 'row', alignItems: 'baseline', justifyContent: 'space-between', gap: SPACE.sm },
  headPadded: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg + 2, paddingBottom: SPACE.sm },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: 60, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  figure: { flex: 1, minWidth: 0, gap: 2 },
  button: { minHeight: 52, borderRadius: RADIUS, borderWidth: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: SPACE.sm, paddingHorizontal: SPACE.lg },
  buttonSmall: { minHeight: 44, borderRadius: RADIUS, borderWidth: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, paddingHorizontal: SPACE.md },
  field: { gap: 6 },
  input: { flexDirection: 'row', alignItems: 'center', minHeight: 52, borderWidth: 1, borderRadius: RADIUS, paddingHorizontal: SPACE.md, gap: SPACE.sm },
  inputMulti: { alignItems: 'stretch' },
  inputError: { borderWidth: 2 },
  fieldError: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  inputText: { flex: 1, minHeight: 48, fontSize: TYPE.body.fontSize },
  notice: { flexDirection: 'row', alignItems: 'flex-start', gap: SPACE.sm, borderRadius: RADIUS, borderWidth: 1, padding: SPACE.md, marginBottom: SPACE.md },
  segment: { flexDirection: 'row', borderRadius: RADIUS, padding: 4, gap: 4 },
  segmentItem: { flex: 1, minHeight: 44, borderRadius: RADIUS_SM, alignItems: 'center', justifyContent: 'center', paddingHorizontal: SPACE.sm },
  chips: { gap: 6, paddingVertical: 2 },
  chip: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 44, paddingHorizontal: SPACE.md, borderRadius: RADIUS, borderWidth: 1 },
  empty: { alignItems: 'center', gap: SPACE.md, paddingVertical: SPACE.xl, paddingHorizontal: SPACE.lg },
  emptyText: { textAlign: 'center' },
  toastWrap: { position: 'absolute', left: SPACE.lg, right: SPACE.lg, bottom: SPACE.md, alignItems: 'center' },
  toast: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, borderRadius: RADIUS, paddingHorizontal: SPACE.lg, paddingVertical: SPACE.md, maxWidth: 480 },
  tag: { borderRadius: 4, paddingHorizontal: 6, paddingVertical: 1, alignSelf: 'flex-start' },
});
