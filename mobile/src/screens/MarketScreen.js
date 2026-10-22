import React, { useMemo, useState } from 'react';
import { FlatList, Pressable, RefreshControl, StyleSheet, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useAccount } from '../account';
import { useAuth } from '../auth';
import { GameIcon } from '../components/GameIcon';
import { price, signedPercent } from '../format';
import { sessionRate } from '../market';
import { FONT_FAMILY, RADIUS, RADIUS_XS, SPACE, TYPE, Txt, toneOf, useTheme } from '../theme';

const FILTERS = [
  { key: 'all', label: '전체' },
  { key: 'up', label: '상승' },
  { key: 'down', label: '하락' },
  { key: 'watch', label: '관심' },
];
const SORTS = [
  { key: 'volume', label: '거래량순' },
  { key: 'rate', label: '등락률순' },
];
const compactVolume = value => {
  const amount = Number(value) || 0;
  if (amount >= 100_000_000) return `${(amount / 100_000_000).toFixed(1)}억주`;
  if (amount >= 10_000) return `${Math.round(amount / 10_000).toLocaleString('ko-KR')}만주`;
  return `${amount.toLocaleString('ko-KR')}주`;
};

/** 시장 목록. 할 일은 종목 찾기 하나다: 검색, 필터, 정렬, 그리고 누르면 상세. */
export function MarketScreen({ market, onOpenStock, onLogin }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { user } = useAuth();
  const { watchlist } = useAccount();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const [sort, setSort] = useState('volume');
  const [refreshing, setRefreshing] = useState(false);

  const rows = useMemo(() => {
    const words = query.trim().toLowerCase();
    return market.stocks
      .map(stock => ({ ...stock, rate: sessionRate(stock) }))
      .filter(stock => !words || stock.name.toLowerCase().includes(words) || stock.code.toLowerCase().includes(words))
      .filter(stock => (filter === 'up' ? stock.rate > 0 : filter === 'down' ? stock.rate < 0 : filter === 'watch' ? watchlist.includes(stock.code) : true))
      .sort((a, b) => (sort === 'volume' ? b.volume - a.volume : filter === 'down' ? a.rate - b.rate : b.rate - a.rate));
  }, [market.stocks, query, filter, sort, watchlist]);

  const refresh = async () => {
    setRefreshing(true);
    await market.refresh();
    setRefreshing(false);
  };

  const empty = () => {
    if (!market.loaded) return <Txt variant="callout" tone="inkSoft" style={styles.empty}>시세를 불러오는 중이에요</Txt>;
    if (market.error && !market.stocks.length) return <Txt variant="callout" tone="inkSoft" style={styles.empty}>시세를 불러오지 못했어요. 아래로 당겨 다시 불러오세요.</Txt>;
    if (filter === 'watch' && !user) {
      return (
        <View style={styles.emptyBox}>
          <Txt variant="callout" tone="inkSoft" style={styles.emptyText}>로그인하면 관심 종목을 모아 볼 수 있어요.</Txt>
          <Pressable accessibilityRole="button" onPress={onLogin} style={({ pressed }) => [styles.emptyButton, { backgroundColor: theme.ink, opacity: pressed ? 0.85 : 1 }]}>
            <Txt variant="callout" style={{ color: theme.ground }}>로그인</Txt>
          </Pressable>
        </View>
      );
    }
    if (filter === 'watch' && !query) return <Txt variant="callout" tone="inkSoft" style={styles.empty}>관심 종목이 아직 없어요. 종목 상세에서 별을 누르면 여기에 모여요.</Txt>;
    return <Txt variant="callout" tone="inkSoft" style={styles.empty}>조건에 맞는 종목이 없어요.</Txt>;
  };

  return (
    <View style={[styles.fill, { backgroundColor: theme.ground }]}>
      <View style={{ paddingTop: insets.top + SPACE.sm, paddingHorizontal: SPACE.lg }}>
        <Txt variant="page" accessibilityRole="header" style={styles.title}>시장</Txt>
        <View style={[styles.search, { backgroundColor: theme.raised, borderColor: theme.edge }]}>
          <Ionicons name="search" size={18} color={theme.inkSoft} />
          <TextInput
            value={query}
            onChangeText={setQuery}
            placeholder="게임 이름이나 종목 코드"
            placeholderTextColor={theme.inkSoft}
            style={[styles.searchInput, { color: theme.ink }]}
            autoCorrect={false}
            autoCapitalize="none"
            returnKeyType="search"
            keyboardAppearance={theme.scheme}
            selectionColor={theme.brand}
            accessibilityLabel="종목 검색"
            clearButtonMode="while-editing"
          />
        </View>
        <View style={styles.controls}>
          <View style={styles.filters} accessibilityRole="tablist">
            {FILTERS.map(item => {
              const selected = filter === item.key;
              return (
                <Pressable
                  key={item.key}
                  accessibilityRole="tab"
                  accessibilityState={{ selected }}
                  onPress={() => setFilter(item.key)}
                  style={[styles.chip, { backgroundColor: selected ? theme.ink : theme.card, borderColor: selected ? theme.ink : theme.edge }]}
                >
                  <Txt variant="caption" style={{ color: selected ? theme.card : theme.ink }}>{item.label}</Txt>
                </Pressable>
              );
            })}
          </View>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`정렬: ${SORTS.find(item => item.key === sort).label}. 눌러서 바꾸기`}
            onPress={() => setSort(current => (current === 'volume' ? 'rate' : 'volume'))}
            hitSlop={8}
            style={styles.sort}
          >
            <Ionicons name="swap-vertical" size={16} color={theme.inkSoft} />
            <Txt variant="caption" tone="inkSoft">{SORTS.find(item => item.key === sort).label}</Txt>
          </Pressable>
        </View>
      </View>
      <FlatList
        data={rows}
        keyExtractor={item => item.code}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        contentContainerStyle={[styles.list, rows.length ? { backgroundColor: theme.card } : null]}
        style={styles.listOuter}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={theme.inkSoft} colors={[theme.ink]} progressBackgroundColor={theme.raised} />}
        ListEmptyComponent={empty}
        renderItem={({ item, index }) => (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`${item.name} ${price(item.price)}원, 오늘 ${signedPercent(item.rate)}${item.restriction ? `, ${item.restriction.label}` : ''}`}
            onPress={() => onOpenStock(item.code)}
            style={({ pressed }) => [styles.row, index > 0 && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line }, pressed && { backgroundColor: theme.pressed }]}
          >
            <GameIcon code={item.code} />
            <View style={styles.name}>
              <View style={styles.nameLine}>
                <Txt variant="callout" numberOfLines={1} style={styles.shrink}>{item.name}</Txt>
                {watchlist.includes(item.code) ? <Ionicons name="star" size={13} color={theme.guard} accessibilityLabel="관심 종목" /> : null}
                {item.restriction ? (
                  <View style={[styles.badge, { backgroundColor: theme.guardWash }]}>
                    <Txt variant="micro" style={{ color: theme.guardText }}>{item.restriction.label}</Txt>
                  </View>
                ) : null}
              </View>
              <Txt variant="caption" tone="inkSoft">{`거래량 ${compactVolume(item.volume)}`}</Txt>
            </View>
            <View style={styles.price}>
              <Txt variant="callout" numeric>{price(item.price)}</Txt>
              <Txt variant="caption" tone={toneOf(item.rate)} numeric>{signedPercent(item.rate)}</Txt>
            </View>
          </Pressable>
        )}
      />
      <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  statusCover: { position: 'absolute', top: 0, left: 0, right: 0 },
  title: { minHeight: 48, paddingTop: SPACE.sm },
  search: { flexDirection: 'row', alignItems: 'center', gap: SPACE.sm, minHeight: 48, borderRadius: RADIUS, borderWidth: 1, paddingHorizontal: SPACE.md, marginTop: SPACE.xs },
  searchInput: { flex: 1, minHeight: 46, fontFamily: FONT_FAMILY, fontSize: TYPE.body.fontSize },
  controls: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: SPACE.md, marginBottom: SPACE.sm },
  filters: { flexDirection: 'row', gap: 6 },
  chip: { minHeight: 44, minWidth: 48, paddingHorizontal: SPACE.md, borderRadius: RADIUS, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  sort: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 36 },
  listOuter: { flex: 1 },
  list: { marginHorizontal: SPACE.lg, marginBottom: SPACE.xxl, borderRadius: RADIUS, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: 68, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  name: { flex: 1, minWidth: 0, gap: 2 },
  nameLine: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  shrink: { flexShrink: 1 },
  badge: { borderRadius: RADIUS_XS, paddingHorizontal: 6, paddingVertical: 1 },
  price: { alignItems: 'flex-end', minWidth: 72 },
  empty: { textAlign: 'center', paddingVertical: SPACE.xxl, paddingHorizontal: SPACE.lg },
  emptyBox: { alignItems: 'center', paddingVertical: SPACE.xxl, gap: SPACE.md },
  emptyText: { textAlign: 'center', paddingHorizontal: SPACE.lg },
  emptyButton: { minHeight: 44, minWidth: 120, borderRadius: RADIUS, alignItems: 'center', justifyContent: 'center', paddingHorizontal: SPACE.lg },
});
