import React, { useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import Svg, { Line, Rect } from 'react-native-svg';
import { price as priceText } from '../format';
import { Txt, useTheme } from '../theme';

const PAD_Y = 18;
// 폰 폭에서 봉이 1px 선으로 뭉개지지 않도록 최근 봉만 그린다.
const MAX_BARS = 60;

const shortTime = (ms, longRange) => {
  const at = new Date(ms);
  const hm = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
  return longRange ? `${at.getMonth() + 1}.${at.getDate()} ${hm}` : hm;
};

/**
 * 캔들 차트. /api/stocks/{code}/chart와 /api/derivatives/futures/chart의 봉을 그대로 받는다.
 * 오른 봉은 적색, 내린 봉은 청색(웹과 같은 한국식). 점선은 기준가다.
 */
export function CandleChart({ candles, reference, format = priceText, height = 168 }) {
  const theme = useTheme();
  const [width, setWidth] = useState(0);

  const shape = useMemo(() => {
    const bars = (candles || [])
      .map(bar => ({ t: Date.parse(bar.recordedAt), o: Number(bar.openPrice), h: Number(bar.highPrice), l: Number(bar.lowPrice), c: Number(bar.closePrice) }))
      .filter(bar => Number.isFinite(bar.t) && bar.h > 0 && bar.l > 0)
      .sort((a, b) => a.t - b.t)
      .slice(-MAX_BARS);
    if (bars.length < 2 || width <= 0) return null;
    const high = Math.max(...bars.map(bar => bar.h));
    const low = Math.min(...bars.map(bar => bar.l));
    const top = Math.max(high, reference > 0 ? reference : high);
    const bottom = Math.min(low, reference > 0 ? reference : low);
    const y = value => (top === bottom ? height / 2 : PAD_Y + (1 - (value - bottom) / (top - bottom)) * (height - PAD_Y * 2));
    const slot = width / bars.length;
    const body = Math.max(1, Math.min(10, slot * 0.6));
    return {
      bars: bars.map((bar, index) => {
        const cx = slot * index + slot / 2;
        const upper = y(Math.max(bar.o, bar.c));
        return { key: bar.t, cx, x: cx - body / 2, w: body, wickTop: y(bar.h), wickBottom: y(bar.l), y: upper, hgt: Math.max(1, y(Math.min(bar.o, bar.c)) - upper), up: bar.c >= bar.o };
      }),
      base: reference > 0 ? y(reference) : null,
      high, low, highY: y(high), lowY: y(low),
      t0: bars[0].t,
      longRange: bars[bars.length - 1].t - bars[0].t > 26 * 60 * 60_000,
    };
  }, [candles, reference, width, height]);

  return (
    <View onLayout={event => setWidth(Math.floor(event.nativeEvent.layout.width))}>
      <View style={{ height }}>
        {shape ? (
          <>
            <Svg width={width} height={height} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
              {shape.base != null ? <Line x1={0} x2={width} y1={shape.base} y2={shape.base} stroke={theme.edge} strokeWidth={1} strokeDasharray="3 4" /> : null}
              {shape.bars.map(bar => {
                const color = bar.up ? theme.up : theme.down;
                return (
                  <React.Fragment key={bar.key}>
                    <Line x1={bar.cx} x2={bar.cx} y1={bar.wickTop} y2={bar.wickBottom} stroke={color} strokeWidth={1} />
                    <Rect x={bar.x} y={bar.y} width={bar.w} height={bar.hgt} fill={color} />
                  </React.Fragment>
                );
              })}
            </Svg>
            <Txt variant="micro" tone="inkSoft" numeric style={[styles.extreme, { top: Math.max(0, shape.highY - 17) }]}>{`최고 ${format(shape.high)}`}</Txt>
            <Txt variant="micro" tone="inkSoft" numeric style={[styles.extreme, { top: Math.min(height - 16, shape.lowY + 3) }]}>{`최저 ${format(shape.low)}`}</Txt>
          </>
        ) : (
          <View style={styles.empty}><Txt variant="caption" tone="inkSoft">이 구간의 봉 기록이 아직 없어요</Txt></View>
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
