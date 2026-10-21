import React, { useRef } from 'react';
import { Animated, Easing, PanResponder, Platform, StyleSheet, View, useWindowDimensions } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTheme } from '../theme';

const EDGE_WIDTH = 24;
// 상세 화면 위쪽 막대(뒤로 가기·관심 버튼)의 높이. 띠가 뒤로 가기 버튼을 덮지 않도록 그 아래부터 받는다.
const HEADER_HEIGHT = 48;
// 아래쪽 매도·매수 버튼 막대의 높이. 띠가 매도 버튼의 왼쪽 끝을 덮지 않게 그 위에서 멈춘다.
const FOOTER_HEIGHT = 72;

/**
 * 아이폰의 '왼쪽 가장자리에서 밀어 뒤로 가기'. 화면이 손가락을 따라 오른쪽으로 밀리며 아래 화면이 드러나고,
 * 화면 폭의 3분의 1을 넘기거나 빠르게 밀면 onBack을 부른다. Android는 시스템 뒤로 가기를 쓰므로 그대로 둔다.
 */
export function SwipeBack({ onBack, children }) {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const offset = useRef(new Animated.Value(0)).current;
  const widthRef = useRef(width);
  widthRef.current = width;
  const backRef = useRef(onBack);
  backRef.current = onBack;

  const responder = useRef(PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: (_, gesture) => gesture.dx > 4 && Math.abs(gesture.dx) > Math.abs(gesture.dy),
    onPanResponderTerminationRequest: () => false,
    onPanResponderMove: (_, gesture) => offset.setValue(Math.max(0, gesture.dx)),
    onPanResponderRelease: (_, gesture) => {
      const done = gesture.dx > widthRef.current / 3 || gesture.vx > 0.5;
      Animated.timing(offset, {
        toValue: done ? widthRef.current : 0,
        duration: done ? 200 : 180,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }).start(({ finished }) => { if (finished && done) backRef.current?.(); });
    },
    onPanResponderTerminate: () => {
      Animated.timing(offset, { toValue: 0, duration: 180, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start();
    },
  })).current;

  if (Platform.OS !== 'ios') return <View style={[StyleSheet.absoluteFill, { backgroundColor: theme.ground }]}>{children}</View>;

  return (
    <Animated.View accessibilityViewIsModal style={[StyleSheet.absoluteFill, styles.page, { backgroundColor: theme.ground, borderLeftColor: theme.line, transform: [{ translateX: offset }] }]}>
      {children}
      {/* 가장자리 띠만 손가락을 받는다. 화면 안쪽의 스크롤과 버튼은 그대로 동작한다. */}
      <View style={[styles.edge, { top: insets.top + HEADER_HEIGHT }]} {...responder.panHandlers} />
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  // 그림자 대신 밀려나는 화면의 왼쪽 괘선으로 아래 화면과 경계를 보여 준다(평평한 무대 규칙).
  page: { borderLeftWidth: 1 },
  edge: { position: 'absolute', left: 0, bottom: FOOTER_HEIGHT, width: EDGE_WIDTH },
});
