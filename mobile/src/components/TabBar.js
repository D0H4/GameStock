import React from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Txt, useTheme } from '../theme';

export const TABS = [
  { key: 'home', label: '홈', icon: 'home', path: null },
  { key: 'market', label: '시장', icon: 'stats-chart', path: null },
  { key: 'news', label: '뉴스', icon: 'newspaper', path: null },
  { key: 'ranking', label: '랭킹', icon: 'trophy', path: null },
  { key: 'more', label: '설정', icon: 'settings', path: null },
];

/**
 * 하단 탭바. 다섯 탭 모두 네이티브 화면이다. 설정 메뉴의 계정 화면만 아직 WebView로 연다.
 * 무대색 바 위에서 현재 탭은 잉크색 글자와 위쪽 2px 핑크 선으로 표시한다(웹 상단 바의 밑줄과 같은 표시).
 */
export function TabBar({ active, onSelect }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View
      accessibilityRole="tablist"
      style={[styles.bar, { backgroundColor: theme.ground, borderTopColor: theme.line, paddingBottom: Math.max(insets.bottom, 6) }]}
    >
      {TABS.map(tab => {
        const selected = tab.key === active;
        const color = selected ? theme.ink : theme.inkSoft;
        return (
          <Pressable
            key={tab.key}
            accessibilityRole="tab"
            accessibilityState={{ selected }}
            accessibilityLabel={tab.label}
            onPress={() => onSelect(tab.key)}
            android_ripple={{ color: theme.pressed, borderless: true }}
            style={styles.item}
          >
            <View style={[styles.mark, selected && { backgroundColor: theme.brand }]} />
            <Ionicons name={selected ? tab.icon : `${tab.icon}-outline`} size={23} color={color} />
            <Txt variant="micro" style={{ color, marginTop: 3 }} maxFontSizeMultiplier={1.2}>{tab.label}</Txt>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: { flexDirection: 'row', borderTopWidth: StyleSheet.hairlineWidth },
  item: { flex: 1, minHeight: 56, alignItems: 'center', justifyContent: 'center', paddingTop: 4 },
  mark: { position: 'absolute', top: 0, width: 28, height: 2 },
});
