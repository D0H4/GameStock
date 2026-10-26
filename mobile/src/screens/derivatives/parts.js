import React, { useRef, useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import Svg, { Line, Polyline } from 'react-native-svg';
import { useAccount } from '../../account';
import { Card, CardHead, Empty, Row, SideTag, kit, kstShort } from '../../components/Kit';
import { closePosition, indexText, openPosition } from '../../derivatives';
import { signedWon, won } from '../../format';
import { NUMBER_FONT, RADIUS, SPACE, TYPE, Txt, useTheme } from '../../theme';

const ACTION_LABEL = { OPEN: '신규', CLOSE: '청산', EXPIRE: '만기', LIQUIDATE: '강제청산' };
const SIDE_LABEL = { BUY: '매수', SELL: '매도', LONG: '롱', SHORT: '숏', CALL: '콜', PUT: '풋' };
const BUY_SIDES = new Set(['BUY', 'LONG', 'CALL']);

/**
 * 주문과 청산을 한 번에 하나만 보내고, 결과 문구를 남긴다. 성공하면 서버가 돌려준 파생 계좌로 바꾸고
 * 주식 계좌(현금)도 다시 읽는다. 로그인 전이면 로그인 시트를 연다.
 */
export function useDerivativeOrder({ signedIn, setAccount, onLogin }) {
  const account = useAccount();
  const pending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);
  const run = async action => {
    if (!signedIn) { onLogin(); return; }
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setNotice(null);
    try {
      const result = await action();
      setAccount(result.account);
      setNotice({ ok: true, text: result.message });
      void account.reload();
    } catch (failure) {
      setNotice({ ok: false, text: failure.message || '주문을 처리하지 못했어요.' });
    } finally {
      pending.current = false;
      setBusy(false);
    }
  };
  return {
    busy,
    notice,
    open: body => void run(() => openPosition(body)),
    close: (id, quantity) => void run(() => closePosition(id, quantity)),
  };
}

/** 시간 없이 값만 있는 짧은 흐름(ETF·선물 series). 점선은 기준가다. */
export function SeriesLine({ series, reference, tone, height = 140 }) {
  const theme = useTheme();
  const [width, setWidth] = useState(0);
  const values = (series ?? []).filter(value => Number.isFinite(value));
  let content = <View style={styles.center}><Txt variant="caption" tone="inkSoft">가격 기록을 모으고 있어요</Txt></View>;
  if (values.length > 1 && width > 0) {
    const top = Math.max(...values, reference ?? -Infinity);
    const bottom = Math.min(...values, reference ?? Infinity);
    const y = value => (top === bottom ? height / 2 : 8 + (1 - (value - bottom) / (top - bottom)) * (height - 16));
    const x = index => (index / (values.length - 1)) * width;
    content = (
      <Svg width={width} height={height} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        {reference ? <Line x1={0} x2={width} y1={y(reference)} y2={y(reference)} stroke={theme.edge} strokeWidth={1} strokeDasharray="3 4" /> : null}
        <Polyline points={values.map((value, index) => `${x(index).toFixed(1)},${y(value).toFixed(1)}`).join(' ')} fill="none" stroke={theme[tone] ?? theme.flat} strokeWidth={2} strokeLinejoin="round" />
      </Svg>
    );
  }
  return <View style={{ height }} onLayout={event => setWidth(Math.floor(event.nativeEvent.layout.width))}>{content}</View>;
}

/** 1~max 정수 입력과 ± 버튼. */
export function Stepper({ label, value, onChange, max = 100, unit }) {
  const theme = useTheme();
  const number = Number(value);
  const valid = Number.isInteger(number) && number >= 1 && number <= max;
  const step = delta => onChange(String(Math.min(max, Math.max(1, (valid ? number : 1) + delta))));
  return (
    <View style={styles.field}>
      <Txt variant="caption" tone="inkSoft">{`${label} · 1~${max}${unit}`}</Txt>
      <View style={[styles.stepper, { borderColor: theme.edge, backgroundColor: theme.raised }]}>
        <StepButton icon="remove" label={`1${unit} 줄이기`} disabled={valid && number <= 1} onPress={() => step(-1)} />
        <TextInput
          value={value}
          onChangeText={text => onChange(text.replace(/[^0-9]/g, '').slice(0, 3))}
          keyboardType="number-pad"
          keyboardAppearance={theme.scheme}
          accessibilityLabel={label}
          style={[styles.stepInput, { color: theme.ink }]}
        />
        <Txt variant="callout" tone="inkSoft">{unit}</Txt>
        <StepButton icon="add" label={`1${unit} 늘리기`} disabled={valid && number >= max} onPress={() => step(1)} />
      </View>
      {value !== '' && !valid ? <Txt variant="caption" tone="up">{`1~${max} 사이의 정수로 입력해 주세요.`}</Txt> : null}
    </View>
  );
}

function StepButton({ icon, label, disabled, onPress }) {
  const theme = useTheme();
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ disabled: Boolean(disabled) }} disabled={disabled} onPress={onPress} style={[styles.stepButton, disabled && { opacity: 0.35 }]}>
      <Ionicons name={icon} size={20} color={theme.ink} />
    </Pressable>
  );
}

/** 이름·값 줄. 주문 요약과 상품 정보에 쓴다. */
export function Fact({ label, value, strong, tone }) {
  return (
    <View style={kit.between}>
      <Txt variant={strong ? 'callout' : 'caption'} tone={strong ? 'ink' : 'inkSoft'}>{label}</Txt>
      <Txt variant={strong ? 'headline' : 'callout'} tone={tone} numeric>{value}</Txt>
    </View>
  );
}

export const pnlTone = amount => (amount > 0 ? 'up' : amount < 0 ? 'down' : 'ink');

/** 최근 체결. 가격 표시는 상품마다 다르다(옵션은 포인트, ETF는 원, 선물은 지수). */
export function TradeList({ title, trades, emptyText }) {
  const priceOf = trade => (trade.kind === 'OPTION' ? `${trade.price.toFixed(2)}p` : trade.kind === 'ETF' ? won(trade.price) : indexText(trade.price));
  return (
    <Card padded={false}>
      <CardHead title={title} meta={trades.length ? `${trades.length}건` : null} padded />
      {trades.length === 0 ? <Empty text={emptyText} /> : trades.map((trade, index) => (
        <Row key={trade.id} divided={index > 0} accessibilityLabel={`${ACTION_LABEL[trade.action] ?? trade.action} ${trade.symbol} ${SIDE_LABEL[trade.side] ?? trade.side} ${trade.quantity}, ${priceOf(trade)}, 현금 ${signedWon(trade.cashFlow)}`}>
          <SideTag buy={BUY_SIDES.has(trade.side)} label={SIDE_LABEL[trade.side] ?? trade.side} />
          <View style={kit.grow}>
            <Txt variant="callout" numberOfLines={1}>{`${ACTION_LABEL[trade.action] ?? trade.action} · ${trade.symbol}`}</Txt>
            <Txt variant="caption" tone="inkSoft" numeric>{`${trade.quantity} · ${priceOf(trade)} · ${kstShort(trade.createdAt)}`}</Txt>
          </View>
          <View style={kit.right}>
            <Txt variant="callout" tone={pnlTone(trade.cashFlow)} numeric>{signedWon(trade.cashFlow)}</Txt>
            <Txt variant="caption" tone="inkSoft" numeric>{`수수료 ${won(trade.fee)}`}</Txt>
          </View>
        </Row>
      ))}
    </Card>
  );
}

/** 주문 결과 한 줄. 성공은 무채색, 실패는 상승 적색을 쓰지 않고 경고 아이콘으로 구분한다. */
export function OrderNotice({ notice }) {
  const theme = useTheme();
  if (!notice) return null;
  return (
    <View style={[styles.notice, { backgroundColor: theme.raised, borderColor: theme.edge }]} accessibilityLiveRegion="polite">
      <Ionicons name={notice.ok ? 'checkmark-circle' : 'alert-circle'} size={18} color={theme.ink} />
      <Txt variant="callout" style={kit.grow}>{notice.text}</Txt>
    </View>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  field: { gap: 6 },
  stepper: { flexDirection: 'row', alignItems: 'center', minHeight: 52, borderWidth: 1, borderRadius: RADIUS, paddingHorizontal: SPACE.xs, gap: SPACE.xs },
  stepButton: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  stepInput: { flex: 1, minHeight: 48, textAlign: 'center', fontFamily: NUMBER_FONT, fontSize: TYPE.headline.fontSize, fontVariant: ['tabular-nums'] },
  notice: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, borderWidth: 1, borderRadius: RADIUS, padding: SPACE.md },
});
