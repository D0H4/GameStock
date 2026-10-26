import React, { useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button, Card, CardHead, Empty, Row, Segment, kit } from '../../components/Kit';
import { GameIcon } from '../../components/GameIcon';
import { ETF_FEE_RATE, indexText, useDerivatives } from '../../derivatives';
import { signedPercent, signedWon, won } from '../../format';
import { SPACE, Txt, toneOf, useTheme } from '../../theme';
import { Fact, OrderNotice, SeriesLine, Stepper, TradeList, pnlTone, useDerivativeOrder } from './parts';

/** ETF 상세. 웹 EtfPanel과 같은 일: NAV와 흐름, 구성 종목, 매수·매도, 보유 청산. */
export function EtfScreen({ code, onBack, onOpenStock, onLogin }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { snapshot, account, error, signedIn, setAccount } = useDerivatives();
  const order = useDerivativeOrder({ signedIn, setAccount, onLogin });
  const [side, setSide] = useState('BUY');
  const [quantity, setQuantity] = useState('10');
  const etf = snapshot?.etfs.find(item => item.code === code);

  const bar = (
    <View style={[styles.bar, { paddingTop: insets.top }]}>
      <Pressable accessibilityRole="button" accessibilityLabel="시장으로 돌아가기" onPress={onBack} style={styles.barButton}>
        <Ionicons name="chevron-back" size={26} color={theme.ink} />
      </Pressable>
    </View>
  );
  if (!etf) {
    return (
      <View style={[styles.fill, { backgroundColor: theme.ground }]}>
        {bar}
        <View style={styles.center}>
          {snapshot ? <Txt variant="callout" tone="inkSoft">{`${code} ETF를 찾을 수 없어요.`}</Txt>
            : error ? <Txt variant="callout" tone="inkSoft">ETF 시세를 불러오지 못했어요. 2초마다 다시 불러와요.</Txt>
            : <ActivityIndicator color={theme.inkSoft} />}
        </View>
      </View>
    );
  }

  const count = Number(quantity);
  const valid = Number.isInteger(count) && count >= 1 && count <= 100;
  const amount = Math.round(etf.price * (valid ? count : 0));
  const fee = Math.ceil(amount * ETF_FEE_RATE);
  const buy = side === 'BUY';
  const holdings = account?.positions.filter(position => position.kind === 'ETF' && position.symbol === code) ?? [];
  const trades = account?.trades.filter(trade => trade.kind === 'ETF' && trade.symbol === code) ?? [];
  const ratio = etf.changePercent / 100;

  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      {bar}
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled" automaticallyAdjustKeyboardInsets>
        <View style={styles.hero}>
          <Txt variant="caption" tone="inkSoft" numeric>{`${etf.code} · ${etf.theme}`}</Txt>
          <Txt variant="title" accessibilityRole="header">{etf.name}</Txt>
          <Txt variant="price" numeric style={{ marginTop: SPACE.sm }}>{won(etf.price)}</Txt>
          <Txt variant="callout" tone={toneOf(ratio)} numeric>{`오늘 ${signedPercent(ratio)} (${signedWon(Math.round(etf.price - etf.prevClose))})`}</Txt>
          {etf.description ? <Txt variant="callout" tone="inkSoft" style={{ marginTop: SPACE.sm }}>{etf.description}</Txt> : null}
        </View>
        {error ? <OrderNotice notice={{ ok: false, text: '시세 갱신이 늦어져 이전 시세를 보여 주고 있어요.' }} /> : null}

        <Card>
          <SeriesLine series={etf.series} reference={etf.prevClose} tone={toneOf(ratio)} />
          <Txt variant="micro" tone="inkSoft">{`최근 가격 흐름 · 점선은 기준가 ${won(etf.prevClose)}`}</Txt>
          <Fact label="NAV" value={indexText(etf.price)} />
          <Fact label="기준가" value={indexText(etf.prevClose)} />
          <Fact label="구성 종목" value={`${etf.constituents.length}개`} />
        </Card>

        <Card>
          <CardHead title="주문" />
          <Segment options={[{ key: 'BUY', label: '매수' }, { key: 'SELL', label: '매도' }]} value={side} onChange={setSide} label="매수·매도" />
          <Stepper label="수량" value={quantity} onChange={setQuantity} unit="주" />
          <Fact label="주문 가능 현금" value={account ? won(account.cash) : signedIn ? '확인 중' : '로그인 후 확인'} />
          <Fact label="주문 금액" value={won(amount)} />
          <Fact label="수수료 0.1%" value={won(fee)} />
          <Fact label={buy ? '빠질 현금' : '들어올 현금'} value={won(buy ? amount + fee : amount - fee)} strong />
          <Button
            label={!signedIn ? '로그인하고 주문하기' : buy ? `${valid ? count : ''}주 매수` : `${valid ? count : ''}주 매도`}
            kind={signedIn ? (buy ? 'buy' : 'sell') : 'primary'}
            busy={order.busy}
            disabled={signedIn && !valid}
            onPress={() => order.open({ kind: 'ETF', side, symbol: etf.code, quantity: count })}
          />
          <OrderNotice notice={order.notice} />
          <Txt variant="caption" tone="inkSoft">ETF는 NAV로 바로 체결되고, 보유 수량은 아래에서 한 번에 팔 수 있어요.</Txt>
        </Card>

        <Card padded={false}>
          <CardHead title="보유 내역" meta={holdings.length ? `${holdings.length}건` : null} padded />
          {!signedIn ? <Empty text="로그인하면 보유 내역을 볼 수 있어요." action="로그인" onAction={onLogin} />
            : !account ? <Empty loading text="계좌를 확인하는 중이에요" />
            : holdings.length === 0 ? <Empty text="보유 중인 수량이 없어요." />
            : holdings.map((position, index) => (
              <Row key={position.id} divided={index > 0}>
                <View style={kit.grow}>
                  <Txt variant="callout" numeric>{`${position.quantity}주 · 평균 ${indexText(position.entryPrice)}`}</Txt>
                  <Txt variant="caption" tone="inkSoft" numeric>{`평가 ${won(position.value)}`}</Txt>
                  <Txt variant="caption" tone={pnlTone(position.unrealizedPnl)} numeric>{`${signedWon(position.unrealizedPnl)} (${signedPercent(position.unrealizedPercent / 100)})`}</Txt>
                </View>
                <Button label="전량 매도" small busy={order.busy} onPress={() => order.close(position.id, position.quantity)} accessibilityLabel={`${position.quantity}주 전량 매도`} />
              </Row>
            ))}
        </Card>

        <Card padded={false}>
          <CardHead title="구성 종목" meta={`${etf.constituents.length}개`} padded />
          {etf.constituents.length === 0 ? <Empty text="이 장르에 상장된 종목이 없어요." /> : etf.constituents.map((item, index) => {
            const itemRatio = item.changePercent / 100;
            return (
              <Row key={item.code} divided={index > 0} onPress={() => onOpenStock(item.code)} accessibilityLabel={`${item.name}, 비중 ${(item.weight * 100).toFixed(1)}%, 오늘 ${signedPercent(itemRatio)}. 누르면 종목 상세`}>
                <GameIcon code={item.code} />
                <Txt variant="callout" numberOfLines={1} style={kit.grow}>{item.name}</Txt>
                <View style={kit.right}>
                  <Txt variant="callout" numeric>{`${(item.weight * 100).toFixed(1)}%`}</Txt>
                  <Txt variant="caption" tone={toneOf(itemRatio)} numeric>{signedPercent(itemRatio)}</Txt>
                </View>
              </Row>
            );
          })}
        </Card>

        {trades.length ? <TradeList title="최근 체결" trades={trades} emptyText="" /> : null}
        <Txt variant="caption" tone="inkSoft" style={styles.disclaimer}>실제 금융상품이 아닌 게임주 기반 모의 거래예요.</Txt>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: SPACE.xl },
  bar: { flexDirection: 'row', paddingHorizontal: SPACE.xs },
  barButton: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  content: { paddingHorizontal: SPACE.lg, paddingBottom: SPACE.xxl },
  hero: { paddingHorizontal: SPACE.xs, paddingBottom: SPACE.lg, gap: 2 },
  disclaimer: { textAlign: 'center', marginTop: SPACE.sm },
});
