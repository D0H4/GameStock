import React, { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { price, signedPercent } from '../format';
import { sessionRate } from '../market';
import { SPACE, Txt, mixColor, useTheme } from '../theme';

/** 시가총액 비중이 이보다 작아도 이만큼의 면적은 준다. 그보다 작으면 등락률 글자가 칸 밖으로 넘친다. */
const MIN_SHARE = 0.03;
const THEME_HEAD = 22;
const GAP = 2;
// 같은 갈래인 세부 장르는 한 구역에 모은다(웹 HeatmapBoard와 같다).
const THEME_OF = { '전략 RPG': 'RPG' };

function worst(row, side) {
  const sum = row.reduce((total, area) => total + area, 0);
  return Math.max((side * side * Math.max(...row)) / (sum * sum), (sum * sum) / (side * side * Math.min(...row)));
}

/** 정사각형에 가까운 칸으로 나누는 트리맵(squarified). 면적이 큰 순서로 받아야 한다. */
function squarify(areas, frame) {
  const boxes = [];
  let rest = { ...frame };
  let row = [];
  const place = () => {
    const sum = row.reduce((total, area) => total + area, 0);
    if (rest.w >= rest.h) {
      const width = sum / rest.h;
      let y = rest.y;
      for (const area of row) { boxes.push({ x: rest.x, y, w: width, h: area / width }); y += area / width; }
      rest = { x: rest.x + width, y: rest.y, w: rest.w - width, h: rest.h };
    } else {
      const height = sum / rest.w;
      let x = rest.x;
      for (const area of row) { boxes.push({ x, y: rest.y, w: area / height, h: height }); x += area / height; }
      rest = { x: rest.x, y: rest.y + height, w: rest.w, h: rest.h - height };
    }
    row = [];
  };
  for (let index = 0; index < areas.length;) {
    const side = Math.min(rest.w, rest.h);
    const next = [...row, areas[index]];
    if (row.length === 0 || worst(next, side) <= worst(row, side)) { row = next; index += 1; } else place();
  }
  if (row.length) place();
  return boxes;
}

/**
 * 장르별 등락 지도. 칸 면적은 시가총액, 색 농도는 등락 크기(1%·3%·5% 경계).
 * 칸 안의 글자는 모두 잉크색이다. 흐린 글자는 가장 진한 칸에서 3.3:1까지 떨어진다.
 */
export function Heatmap({ stocks, onOpenStock, height = 520 }) {
  const theme = useTheme();
  const [width, setWidth] = useState(0);
  const total = stocks.reduce((sum, stock) => sum + (stock.marketCap ?? 0), 0) || 1;
  const themes = new Map();
  for (const stock of stocks) {
    const name = THEME_OF[stock.genre] ?? stock.genre ?? '기타';
    const weight = Math.max(MIN_SHARE, (stock.marketCap ?? 0) / total);
    themes.set(name, [...(themes.get(name) ?? []), { stock, weight }]);
  }
  const groups = [...themes].map(([name, items]) => ({
    name,
    items: items.sort((a, b) => b.weight - a.weight),
    weight: items.reduce((sum, item) => sum + item.weight, 0),
  })).sort((a, b) => b.weight - a.weight);
  const weightSum = groups.reduce((sum, group) => sum + group.weight, 0) || 1;
  const outer = { x: 0, y: 0, w: width, h: height };
  const groupBoxes = width > 0 ? squarify(groups.map(group => (group.weight / weightSum) * width * height), outer) : [];

  return (
    <View style={{ height }} onLayout={event => setWidth(Math.floor(event.nativeEvent.layout.width))} accessibilityLabel="장르별 종목 등락 지도. 칸 크기는 시가총액">
      {groupBoxes.map((area, groupIndex) => {
        const group = groups[groupIndex];
        const inner = { x: 0, y: THEME_HEAD, w: Math.max(0, area.w - GAP), h: Math.max(0, area.h - GAP - THEME_HEAD) };
        const boxes = squarify(group.items.map(item => (item.weight / group.weight) * inner.w * inner.h), inner);
        return (
          <View key={group.name} style={[styles.group, { left: area.x, top: area.y, width: area.w, height: area.h }]}>
            <Txt variant="micro" tone="inkSoft" numberOfLines={1} style={styles.theme}>{group.name}</Txt>
            {group.items.map(({ stock }, index) => {
              const box = boxes[index];
              const ratio = sessionRate(stock);
              // 칸에 찍히는 소수 둘째 자리 값으로 나눠야 "0.00%" 칸이 색을 띠지 않는다.
              const percent = Math.abs(Number((ratio * 100).toFixed(2)));
              const level = percent === 0 ? 0 : percent < 1 ? 1 : percent < 3 ? 2 : percent < 5 ? 3 : 4;
              const hue = ratio > 0 ? theme.upFill : theme.downFill;
              const face = level === 0 ? theme.raised : mixColor(theme.card, hue, 1 - [0, 0.15, 0.25, 0.35, 0.45][level]);
              const small = box.w < 72 || box.h < 54;
              return (
                <Pressable
                  key={stock.code}
                  accessibilityRole="button"
                  accessibilityLabel={`${stock.name} ${signedPercent(ratio)}, ${price(stock.price)}원${stock.restriction ? `, ${stock.restriction.label}` : ''}`}
                  onPress={() => onOpenStock(stock.code)}
                  style={({ pressed }) => [styles.tile, { left: box.x, top: box.y, width: Math.max(0, box.w - GAP), height: Math.max(0, box.h - GAP), backgroundColor: face, opacity: pressed ? 0.8 : 1 }]}
                >
                  <Txt variant={small ? 'micro' : 'caption'} numberOfLines={small ? 1 : 2} style={styles.center}>{stock.name}</Txt>
                  <Txt variant={small ? 'micro' : 'callout'} numeric style={styles.center}>{signedPercent(ratio)}</Txt>
                  {!small ? <Txt variant="micro" numeric style={styles.center}>{`${price(stock.price)}원`}</Txt> : null}
                  {stock.restriction && !small ? <Txt variant="micro" style={styles.center}>{stock.restriction.label}</Txt> : null}
                </Pressable>
              );
            })}
          </View>
        );
      })}
      {stocks.length === 0 ? <Txt variant="callout" tone="inkSoft" style={[styles.center, { marginTop: SPACE.xxl }]}>시세를 불러오고 있어요</Txt> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  group: { position: 'absolute' },
  theme: { height: THEME_HEAD, paddingHorizontal: 4, textAlignVertical: 'center', lineHeight: THEME_HEAD },
  tile: { position: 'absolute', borderRadius: 4, alignItems: 'center', justifyContent: 'center', padding: 4, overflow: 'hidden' },
  center: { textAlign: 'center' },
});
