import React, { useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import Svg, { Circle, Line, Polyline } from 'react-native-svg';
import { price } from '../format';
import { Txt, useTheme } from '../theme';

const PAD_X = 4;
const PAD_Y = 18;

const shortTime = (ms, longRange) => {
  const at = new Date(ms);
  const hm = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
  return longRange ? `${at.getMonth() + 1}.${at.getDate()} ${hm}` : hm;
};

/**
 * 종목 상세의 가격 차트. 점선은 오늘 기준가, 흰 점은 관련 뉴스가 나온 시점이다.
 * 구간의 최고·최저가와 시작·끝 시각만 글자로 적고, 나머지 눈금은 두지 않는다.
 */
export function LineChart({ points, current, reference, newsTimes = [], tone, height = 168 }) {
  const theme = useTheme();
  const [width, setWidth] = useState(0);

  const shape = useMemo(() => {
    const series = (points || [])
      .map(point => ({ t: Date.parse(point.recordedAt), p: Number(point.price) }))
      .filter(point => Number.isFinite(point.t) && point.p > 0)
      .sort((a, b) => a.t - b.t);
    if (current > 0) series.push({ t: Date.now(), p: current });
    if (series.length < 2 || width <= 0) return null;
    const t0 = series[0].t;
    const t1 = series[series.length - 1].t;
    const values = series.map(point => point.p);
    const high = Math.max(...values);
    const low = Math.min(...values);
    const top = Math.max(high, reference > 0 ? reference : high);
    const bottom = Math.min(low, reference > 0 ? reference : low);
    const x = t => PAD_X + ((t - t0) / Math.max(1, t1 - t0)) * (width - PAD_X * 2);
    const y = p => (top === bottom ? height / 2 : PAD_Y + (1 - (p - bottom) / (top - bottom)) * (height - PAD_Y * 2));
    const markers = newsTimes
      .map(iso => Date.parse(iso))
      .filter(t => Number.isFinite(t) && t >= t0 && t <= t1)
      .map(t => {
        const before = series.filter(point => point.t <= t).pop() ?? series[0];
        return { cx: x(t), cy: y(before.p) };
      });
    return {
      line: series.map(point => `${x(point.t).toFixed(1)},${y(point.p).toFixed(1)}`).join(' '),
      base: reference > 0 ? y(reference) : null,
      high, low, highY: y(high), lowY: y(low), t0, t1,
      longRange: t1 - t0 > 26 * 60 * 60_000,
      markers,
    };
  }, [points, current, reference, newsTimes, width, height]);

  return (
    <View onLayout={event => setWidth(Math.floor(event.nativeEvent.layout.width))}>
      <View style={{ height }}>
        {shape ? (
          <>
            <Svg width={width} height={height} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
              {shape.base != null ? <Line x1={0} x2={width} y1={shape.base} y2={shape.base} stroke={theme.chartBase} strokeWidth={1} strokeDasharray="3 4" /> : null}
              <Polyline points={shape.line} fill="none" stroke={theme[tone] ?? theme.flat} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
              {shape.markers.map((marker, index) => (
                <Circle key={index} cx={marker.cx} cy={marker.cy} r={4} fill="#FFFFFF" stroke={theme.ink} strokeWidth={1.5} />
              ))}
            </Svg>
            <Txt variant="micro" tone="inkSoft" numeric style={[styles.extreme, { top: Math.max(0, shape.highY - 17) }]}>{`최고 ${price(shape.high)}`}</Txt>
            <Txt variant="micro" tone="inkSoft" numeric style={[styles.extreme, { top: Math.min(height - 16, shape.lowY + 3) }]}>{`최저 ${price(shape.low)}`}</Txt>
          </>
        ) : (
          <View style={styles.empty}><Txt variant="caption" tone="inkSoft">이 구간의 가격 기록이 아직 없어요</Txt></View>
        )}
      </View>
      {shape ? (
        <View style={styles.axis}>
          <Txt variant="micro" tone="inkSoft">{shortTime(shape.t0, shape.longRange)}</Txt>
          <Txt variant="micro" tone="inkSoft">지금</Txt>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  extreme: { position: 'absolute', right: 0 },
  empty: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  axis: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 },
});
