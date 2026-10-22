import React, { useMemo } from 'react';
import Svg, { Circle, Line, Polyline } from 'react-native-svg';
import { useTheme } from '../theme';

/**
 * 24시간 가격 기록을 작은 선으로 그린다. 점선은 기준가, 흰 점은 마지막 관련 뉴스가 나온 시점이다.
 * 뉴스가 기록 구간 밖이면 점을 찍지 않는다.
 */
export function PriceTrail({ points, current, reference, newsAt, tone, width = 64, height = 30, strokeWidth = 1.75 }) {
  const theme = useTheme();
  const shape = useMemo(() => {
    const series = (points || []).map(point => ({ t: Date.parse(point.recordedAt), p: Number(point.price) }))
      .filter(point => Number.isFinite(point.t) && point.p > 0);
    if (current > 0) series.push({ t: Date.now(), p: current });
    if (series.length < 2) return null;
    const t0 = series[0].t;
    const t1 = series[series.length - 1].t;
    const prices = series.map(point => point.p).concat(reference > 0 ? [reference] : []);
    const low = Math.min(...prices);
    const high = Math.max(...prices);
    const pad = 3;
    const x = t => pad + ((t - t0) / Math.max(1, t1 - t0)) * (width - pad * 2);
    const y = p => (high === low ? height / 2 : pad + (1 - (p - low) / (high - low)) * (height - pad * 2));
    const line = series.map(point => `${x(point.t).toFixed(1)},${y(point.p).toFixed(1)}`).join(' ');
    let marker = null;
    const news = Date.parse(newsAt);
    if (Number.isFinite(news) && news >= t0 && news <= t1) {
      // 뉴스 시각 직전의 가격 위에 점을 둔다.
      const before = series.filter(point => point.t <= news).pop() ?? series[0];
      marker = { cx: x(news), cy: y(before.p) };
    }
    return { line, marker, base: reference > 0 ? y(reference) : null };
  }, [points, current, reference, newsAt, width, height]);

  if (!shape) return <Svg width={width} height={height} />;
  const stroke = theme[tone] ?? theme.flat;
  return (
    <Svg width={width} height={height} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      {shape.base != null && <Line x1={0} x2={width} y1={shape.base} y2={shape.base} stroke={theme.edge} strokeWidth={1} strokeDasharray="2 3" />}
      <Polyline points={shape.line} fill="none" stroke={stroke} strokeWidth={strokeWidth} strokeLinejoin="round" strokeLinecap="round" />
      {/* 흰 점. 라이트의 흰 카드 위에서도 보이도록 진한 테두리를 두른다. */}
      {shape.marker && <Circle cx={shape.marker.cx} cy={shape.marker.cy} r={3.75} fill={theme.dot} stroke={theme.dotRing} strokeWidth={1.5} />}
    </Svg>
  );
}
