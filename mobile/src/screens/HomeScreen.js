import React, { useMemo, useState } from 'react';
import { ActivityIndicator, Image, Pressable, RefreshControl, ScrollView, StyleSheet, View, useWindowDimensions } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { GameIcon } from '../components/GameIcon';
import { PriceTrail } from '../components/PriceTrail';
import { cleanTitle, indexValue, price, relativeTime, signedPercent } from '../format';
import { computeIndex, indexTrail, newsWithIds, sessionRate, usePriceHistory } from '../market';
import { RADIUS, RADIUS_XS, SPACE, Txt, toneOf, useTheme } from '../theme';

const MOVER_COUNT = 5;
const DAY_MS = 24 * 60 * 60_000;
// 종목 줄의 고정 폭: 화면 여백 32, 줄 안쪽 여백 32, 아이콘 40, 간격 30, 추이선 64, 가격 칸 72.
// 이름에 남는 폭이 이보다 좁으면 추이선을 빼고 이름에 자리를 준다.
const TRAIL_WIDTH = 64;
const MIN_NAME_WIDTH = 96;
// 종목 추이선을 숨기는 좁은 화면에서 쓰는 지수 그래프 폭. 넓은 화면에서는 아래 indexTrailWidth로 계산한다.
const INDEX_TRAIL_WIDTH = 80;
const PRICE_COLUMN = 72;
const INDEX_TRAIL_HEIGHT = 36;

export function HomeScreen({ market, onOpen }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { width, fontScale } = useWindowDimensions();
  // 가격 칸은 글자 크기 배율만큼 넓힌 고정 폭이다. 그래야 종목 줄 추이선과 지수 그래프가 한 세로줄에 선다.
  const priceColumn = Math.ceil(PRICE_COLUMN * Math.min(fontScale, 1.4));
  const nameRoom = width - 32 - 32 - 40 - 30 - TRAIL_WIDTH - priceColumn;
  const showTrail = nameRoom >= MIN_NAME_WIDTH;
  // 지수 그래프: 시작은 종목 줄 추이선의 왼쪽 끝, 끝은 카드 안쪽 오른쪽 경계("전체 종목 >" 화살표와 같은 선).
  const indexTrailWidth = showTrail ? TRAIL_WIDTH + SPACE.md + priceColumn : INDEX_TRAIL_WIDTH;
  const [refreshing, setRefreshing] = useState(false);
  const { stocks, events, status, loaded, error } = market;

  const view = useMemo(() => {
    const news = newsWithIds(events);
    const latestByCode = {};
    for (const item of news) if (!latestByCode[item.stockCode]) latestByCode[item.stockCode] = item;
    const rated = stocks.map(stock => ({ ...stock, rate: sessionRate(stock) }));
    const movers = [...rated].sort((a, b) => Math.abs(b.rate) - Math.abs(a.rate)).slice(0, MOVER_COUNT);
    const names = Object.fromEntries(stocks.map(stock => [stock.code, stock.name]));
    return { news: news.slice(0, 3), latestByCode, movers, names };
  }, [stocks, events]);

  // 지수 흐름은 전 종목 기록으로 다시 계산하므로 기록과 기준가가 바뀔 때만 계산한다.
  const history = usePriceHistory(stocks.map(stock => stock.code));
  const baseKey = stocks.map(stock => `${stock.code}:${stock.referencePrice}:${stock.marketCap}`).join(',');
  const trail = useMemo(() => indexTrail(stocks, history), [history, baseKey]); // eslint-disable-line react-hooks/exhaustive-deps
  // 실시간 시세로 계산한 현재 지수. 서버 식과 같고, 시세가 없을 때만 서버 값을 쓴다.
  const liveIndex = computeIndex(stocks) ?? status?.indexValue;
  const indexRatio = liveIndex != null ? (liveIndex - 1000) / 1000 : 0;
  const restriction = status?.restriction;

  const refresh = async () => {
    setRefreshing(true);
    await market.refresh();
    setRefreshing(false);
  };

  if (!loaded) {
    return (
      <View style={[styles.center, { backgroundColor: theme.ground }]} accessibilityLiveRegion="polite">
        <ActivityIndicator color={theme.inkSoft} />
        <Txt variant="callout" tone="inkSoft" style={{ marginTop: SPACE.md }}>시세와 뉴스를 불러오는 중이에요</Txt>
      </View>
    );
  }

  return (
    <View style={styles.fill}>
    <ScrollView
      style={{ backgroundColor: theme.ground }}
      contentContainerStyle={{ paddingTop: insets.top + SPACE.sm, paddingBottom: SPACE.xxl, paddingHorizontal: SPACE.lg }}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={theme.inkSoft} colors={[theme.ink]} progressBackgroundColor={theme.raised} progressViewOffset={insets.top} />}
    >
      <View style={styles.header}>
        <Image source={require('../../assets/brand/sn-mark.png')} style={styles.mark} accessibilityIgnoresInvertColors />
        <Txt variant="headline" accessibilityRole="header">슈엔증권</Txt>
      </View>

      {error && stocks.length === 0 ? (
        <Card>
          <Txt variant="headline">시세를 불러오지 못했어요</Txt>
          <Txt variant="callout" tone="inkSoft" style={{ marginTop: SPACE.xs }}>네트워크 연결을 확인한 뒤 화면을 아래로 당겨 다시 불러오세요.</Txt>
        </Card>
      ) : null}

      {error && stocks.length > 0 ? (
        <View style={[styles.notice, { backgroundColor: theme.raised }]} accessibilityLiveRegion="polite">
          <Ionicons name="cloud-offline" size={18} color={theme.inkSoft} />
          <Txt variant="caption" style={{ flex: 1 }}>새 시세를 받지 못해 마지막으로 받은 값을 보여 주고 있어요. 아래로 당겨 다시 불러오세요.</Txt>
        </View>
      ) : null}

      {restriction ? (
        <View style={[styles.notice, { backgroundColor: theme.guardWash }]}>
          <Ionicons name="shield-half" size={18} color={theme.guardText} />
          <Txt variant="caption" style={{ color: theme.guardText, flex: 1 }}>{`${restriction.label} · ${restriction.effect || restriction.reason}`}</Txt>
        </View>
      ) : null}

      {/* 지수는 화면의 중심이라 카드에 넣지 않고 바탕 위에 둔다. 좌우 여백은 카드 안쪽 여백과 같아서
          그래프가 아래 종목 줄 추이선과 같은 세로줄에 선다. */}
      {liveIndex != null ? (
        <View style={styles.indexHero}>
          <View style={styles.indexRow}>
            <View style={styles.indexValue} accessible accessibilityLabel={`게임주 지수 ${indexValue(liveIndex)}, 오늘 ${signedPercent(indexRatio)}`}>
              <Txt variant="caption" tone="inkSoft">게임주 지수</Txt>
              <Txt variant="hero" numeric numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.6} style={{ marginTop: 2 }}>{indexValue(liveIndex)}</Txt>
              <Delta ratio={indexRatio} variant="callout" label="오늘" />
            </View>
            {/* 좁은 화면에서는 종목 추이선과 범례를 숨기므로 지수 그래프도 함께 뺀다(맞출 세로줄과 점선 설명이 없다).
                기록이 다 오기 전이나 일부 실패하면 자리만 비워 두고, 지어낸 선을 그리지 않는다. */}
            {showTrail ? (
              trail ? (
                <View accessible accessibilityLabel={`오늘 0시부터 지금까지의 게임주 지수 흐름. 점선은 기준 1,000`}>
                  <PriceTrail
                    points={trail.points}
                    current={liveIndex}
                    reference={1000}
                    tone={toneOf(indexRatio)}
                    width={indexTrailWidth}
                    height={INDEX_TRAIL_HEIGHT}
                  />
                </View>
              ) : (
                <View style={{ width: indexTrailWidth, height: INDEX_TRAIL_HEIGHT }} />
              )
            ) : null}
          </View>
        </View>
      ) : null}

      {view.movers.length ? (
        <Card padded={false}>
          <SectionHead title="오늘 많이 움직인 종목" action="전체 종목" onAction={() => onOpen('market')} />
          {view.movers.map((stock, index) => {
            const news = view.latestByCode[stock.code];
            const recent = news && Date.now() - news.at < DAY_MS ? news : null;
            const reason = recent ? `${cleanTitle(recent.title)} · ${relativeTime(recent.publishedAt)}` : null;
            return (
              <Pressable
                key={stock.code}
                accessibilityRole="button"
                accessibilityLabel={`${stock.name} ${price(stock.price)}원, 오늘 ${signedPercent(stock.rate)}. ${reason ? `관련 뉴스: ${reason}` : '최근 24시간 관련 뉴스 없음'}`}
                onPress={() => onOpen('market', `/market/${stock.code}`)}
                style={({ pressed }) => [styles.mover, index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }, pressed && { backgroundColor: theme.pressed }]}
              >
                <View style={styles.moverTop}>
                  <GameIcon code={stock.code} />
                  <View style={styles.moverName}>
                    <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{stock.name}</Txt>
                    {stock.restriction ? (
                      <View style={[styles.badge, { backgroundColor: theme.guardWash }]}>
                        <Txt variant="micro" style={{ color: theme.guardText }}>{stock.restriction.label}</Txt>
                      </View>
                    ) : null}
                  </View>
                  {showTrail ? (
                    <PriceTrail points={history[stock.code]} current={stock.price} reference={stock.referencePrice} newsAt={recent?.publishedAt} tone={toneOf(stock.rate)} width={TRAIL_WIDTH} />
                  ) : null}
                  <View style={[styles.moverPrice, { width: priceColumn }]}>
                    <Txt variant="callout" numeric>{price(stock.price)}</Txt>
                    <Txt variant="caption" tone={toneOf(stock.rate)} numeric>{signedPercent(stock.rate)}</Txt>
                  </View>
                </View>
                <Txt variant="caption" tone="inkSoft" numberOfLines={1} style={styles.reason}>
                  {reason ? <><Txt variant="caption" tone="brandText">뉴스</Txt>{` · ${reason}`}</> : '최근 24시간 관련 뉴스 없음'}
                </Txt>
              </Pressable>
            );
          })}
          {showTrail ? <Txt variant="micro" tone="inkSoft" style={styles.legend}>흰 점은 마지막 관련 뉴스가 나온 시점, 점선은 기준가예요.</Txt> : null}
        </Card>
      ) : !error ? (
        <Card>
          <Txt variant="headline">오늘 많이 움직인 종목</Txt>
          <Txt variant="callout" tone="inkSoft" style={{ marginTop: SPACE.xs }}>서버에 상장된 종목이 아직 없어요. 종목이 등록되면 여기에 보여요.</Txt>
        </Card>
      ) : null}

      {view.news.length ? (
        <Card padded={false}>
          <SectionHead title="최신 뉴스" action="전체 뉴스" onAction={() => onOpen('news')} />
          {view.news.map((item, index) => (
            <Pressable
              key={item.id}
              accessibilityRole="button"
              onPress={() => onOpen('news', `/news/${item.id}`)}
              style={({ pressed }) => [styles.news, index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }, pressed && { backgroundColor: theme.pressed }]}
            >
              <Txt variant="callout" numberOfLines={2}>{cleanTitle(item.title)}</Txt>
              <Txt variant="caption" tone="inkSoft" style={{ marginTop: SPACE.xs }}>
                {`${view.names[item.stockCode] ?? '시장 전체'} · ${relativeTime(item.publishedAt)}`}
              </Txt>
            </Pressable>
          ))}
        </Card>
      ) : !error ? (
        <Card padded={false}>
          <SectionHead title="최신 뉴스" action="전체 뉴스" onAction={() => onOpen('news')} />
          <Txt variant="callout" tone="inkSoft" style={styles.news}>아직 수집된 뉴스가 없어요. 새 기사가 들어오면 여기에 보여요.</Txt>
        </Card>
      ) : null}

      <Txt variant="micro" tone="inkSoft" style={styles.footer}>모의 시장이에요. 실제 주식이나 현금을 거래하지 않아요.</Txt>
    </ScrollView>
    {/* 스크롤한 카드가 상태 표시줄 글자 밑으로 지나가지 않도록 위쪽 안전 영역을 바탕색으로 덮는다. */}
    <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
    </View>
  );
}

function Card({ children, padded = true }) {
  const theme = useTheme();
  return <View style={[styles.card, { backgroundColor: theme.card }, padded && styles.cardPadded]}>{children}</View>;
}

function SectionHead({ title, action, onAction }) {
  const theme = useTheme();
  return (
    <View style={[styles.rowBetween, styles.sectionHead]}>
      <Txt variant="headline" accessibilityRole="header">{title}</Txt>
      <Pressable accessibilityRole="button" onPress={onAction} hitSlop={12} style={styles.sectionAction}>
        <Txt variant="caption" tone="inkSoft">{action}</Txt>
        <Ionicons name="chevron-forward" size={14} color={theme.inkSoft} />
      </Pressable>
    </View>
  );
}

/** label은 등락의 비교 기준. 지수와 종목 등락률은 모두 오늘 기준가 대비다. */
function Delta({ ratio, variant, label }) {
  const theme = useTheme();
  const tone = toneOf(ratio);
  const icon = ratio > 0 ? 'caret-up' : ratio < 0 ? 'caret-down' : 'remove';
  return (
    <View style={styles.delta}>
      {label ? <Txt variant={variant} tone="inkSoft" style={styles.deltaLabel}>{label}</Txt> : null}
      <Ionicons name={icon} size={14} color={theme[tone]} />
      <Txt variant={variant} tone={tone} numeric>{signedPercent(ratio)}</Txt>
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: SPACE.xl },
  statusCover: { position: 'absolute', top: 0, left: 0, right: 0 },
  shrink: { flexShrink: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, minHeight: 48, marginBottom: SPACE.sm },
  mark: { width: 30, height: 30, borderRadius: 8 },
  notice: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, borderRadius: RADIUS, paddingVertical: SPACE.md, paddingHorizontal: SPACE.md, marginBottom: SPACE.md },
  card: { borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  cardPadded: { padding: SPACE.lg + 2 },
  rowBetween: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  indexHero: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.xs, paddingBottom: SPACE.xl },
  indexRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: SPACE.lg },
  deltaLabel: { marginRight: 4 },
  indexValue: { flex: 1, minWidth: 0 },
  delta: { flexDirection: 'row', alignItems: 'center', gap: 2 },
  sectionHead: { paddingHorizontal: SPACE.lg + 2, paddingTop: SPACE.lg + 2, paddingBottom: SPACE.sm },
  sectionAction: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 32 },
  mover: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  moverTop: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md },
  moverName: { flex: 1, minWidth: 0, flexDirection: 'row', alignItems: 'center', gap: 6 },
  moverPrice: { alignItems: 'flex-end' },
  badge: { borderRadius: RADIUS_XS, paddingHorizontal: 6, paddingVertical: 1 },
  reason: { marginTop: 6, marginLeft: 40 + SPACE.md },
  legend: { paddingHorizontal: SPACE.lg + 2, paddingBottom: SPACE.md, paddingTop: SPACE.xs },
  news: { paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  footer: { textAlign: 'center', marginTop: SPACE.sm },
});
