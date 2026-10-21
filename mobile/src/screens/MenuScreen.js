import React from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { RADIUS, SPACE, Txt, useTheme } from '../theme';

// 웹 계정 메뉴(ProfileMenu)의 항목. 화면은 모두 웹이 그리고, 로그인하지 않았으면 웹이 로그인 창을 먼저 띄운다.
// 관리자 화면은 일반 사용자에게 보이면 안 되므로 여기 두지 않고, 웹 화면의 계정 메뉴에만 남긴다.
const ACCOUNT = { label: '내 계좌', detail: '보유 종목과 손익, 주문·체결 내역', icon: 'wallet-outline', path: '/mypage' };
const ITEMS = [
  { label: '충전하기', detail: '오늘의 투자 미션과 모의 자금 보상', icon: 'checkbox-outline', path: '/charge' },
  { label: '대출', detail: '모의 대출과 상환, 한도와 이자', icon: 'cash-outline', path: '/loan' },
  { label: '칭호 설정', detail: '칭호 조건과 획득 현황, 장착', icon: 'ribbon-outline', path: '/titles' },
  { label: '프로필·계정 설정', detail: '닉네임, 프로필 사진, 계좌 리셋', icon: 'person-circle-outline', path: '/settings' },
];

export function MenuScreen({ onOpen }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View style={styles.fill}>
      <ScrollView
        style={{ backgroundColor: theme.ground }}
        contentContainerStyle={{ paddingTop: insets.top + SPACE.sm, paddingBottom: SPACE.xxl, paddingHorizontal: SPACE.lg }}
      >
        <Txt variant="title" accessibilityRole="header" style={styles.title}>설정</Txt>
        <View style={[styles.card, { backgroundColor: theme.card }]}>
          <Row item={ACCOUNT} onOpen={onOpen} large />
        </View>
        <View style={[styles.card, { backgroundColor: theme.card }]}>
          {ITEMS.map((item, index) => <Row key={item.path} item={item} onOpen={onOpen} divided={index > 0} />)}
        </View>
        <Txt variant="caption" tone="inkSoft" style={styles.note}>로그인이 필요한 화면은 열 때 로그인 창이 먼저 떠요.</Txt>
      </ScrollView>
      <View pointerEvents="none" style={[styles.statusCover, { height: insets.top, backgroundColor: theme.ground }]} />
    </View>
  );
}

function Row({ item, onOpen, divided, large }) {
  const theme = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${item.label}. ${item.detail}`}
      onPress={() => onOpen(item.path)}
      style={({ pressed }) => [
        styles.row,
        large && styles.rowLarge,
        divided && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: theme.line },
        pressed && { backgroundColor: theme.pressed },
      ]}
    >
      <Ionicons name={item.icon} size={large ? 26 : 22} color={theme.ink} />
      <View style={styles.rowText}>
        <Txt variant={large ? 'headline' : 'callout'}>{item.label}</Txt>
        <Txt variant="caption" tone="inkSoft" style={{ marginTop: 2 }}>{item.detail}</Txt>
      </View>
      <Ionicons name="chevron-forward" size={18} color={theme.inkSoft} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  statusCover: { position: 'absolute', top: 0, left: 0, right: 0 },
  title: { minHeight: 48, textAlignVertical: 'center', marginBottom: SPACE.sm, paddingTop: SPACE.sm },
  card: { borderRadius: RADIUS, marginBottom: SPACE.md, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', gap: SPACE.md, minHeight: 64, paddingHorizontal: SPACE.lg + 2, paddingVertical: SPACE.md },
  rowLarge: { minHeight: 76 },
  rowText: { flex: 1, minWidth: 0 },
  note: { textAlign: 'center', marginTop: SPACE.sm },
});
