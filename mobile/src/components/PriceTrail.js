import React, { useMemo, useState } from 'react';
import { View } from 'react-native';
import Svg, { Circle, Line, Polygon, Polyline } from 'react-native-svg';
import { useTheme } from '../theme';

// 뉴스 표시가 놓이는 아래 띠의 높이. 표시가 가격 선 위에 겹치지 않도록 그래프와 따로 둔다.
const NEWS_LANE = 8;

/**
 * 24시간 가격 기록을 선으로 그린다. 점선은 기준가다.
 * 마지막 관련 뉴스 시점은 선 뒤로 지나는 세로선과 그래프 아래 띠의 점으로 표시해 선을 가리지 않는다.
 * width를 주지 않으면 놓인 자리의 폭을 재서 채운다. newsLane이면 뉴스가 없는 줄도 같은 높이의 띠를 비워 둔다.
 * area면 선 아래를 오름·내림 옅은 색으로 채워 작은 크기에서도 흐름의 모양이 읽히게 한다.
 */
export function PriceTrail({ points, current, reference, newsAt, newsLane = false, area = false, tone, width, height = 30, strokeWidth = 1.75 }) {
  const theme = useTheme();
  const [measured, setMeasured] = useState(0);
  const drawWidth = width ?? measured;
  const lane = newsLane ? NEWS_LANE : 0;
  const chartHeight = height - lane;
  const shape = useMemo(() => {
    const series = (points || []).map(point => ({ t: Date.parse(point.recordedAt), p: Number(point.price) }))
      .filter(point => Number.isFinite(point.t) && point.p > 0);
    if (current > 0) series.push({ t: Date.now(), p: current });
    if (series.length < 2 || drawWidth <= 0) return null;
    const t0 = series[0].t;
    const t1 = series[series.length - 1].t;
    const prices = series.map(point => point.p).concat(reference > 0 ? [reference] : []);
    const low = Math.min(...prices);
    const high = Math.max(...prices);
    const pad = 3;
    const x = t => pad + ((t - t0) / Math.max(1, t1 - t0)) * (drawWidth - pad * 2);
    const y = p => (high === low ? chartHeight / 2 : pad + (1 - (p - low) / (high - low)) * (chartHeight - pad * 2));
    const news = Date.parse(newsAt);
    const line = series.map(point => `${x(point.t).toFixed(1)},${y(point.p).toFixed(1)}`).join(' ');
    return {
      line,
      fill: `${x(t0).toFixed(1)},${chartHeight} ${line} ${x(t1).toFixed(1)},${chartHeight}`,
      base: reference > 0 ? y(reference) : null,
      newsX: lane && Number.isFinite(news) && news >= t0 && news <= t1 ? x(news) : null,
    };
  }, [points, current, reference, newsAt, drawWidth, chartHeight]);

  const stroke = theme[tone] ?? theme.flat;
  const wash = tone === 'up' ? theme.upWash : tone === 'down' ? theme.downWash : theme.raised;
  const svg = shape ? (
    <Svg width={drawWidth} height={height} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      {area && <Polygon points={shape.fill} fill={wash} />}
      {shape.base != null && <Line x1={0} x2={drawWidth} y1={shape.base} y2={shape.base} stroke={theme.edge} strokeWidth={1} strokeDasharray="2 3" />}
      {/* 뉴스 세로선은 가격 선보다 먼저 그려 선 아래로 지나가게 한다. */}
      {shape.newsX != null && <Line x1={shape.newsX} x2={shape.newsX} y1={0} y2={chartHeight} stroke={theme.edge} strokeWidth={1} />}
      <Polyline points={shape.line} fill="none" stroke={stroke} strokeWidth={strokeWidth} strokeLinejoin="round" strokeLinecap="round" />
      {shape.newsX != null && <Circle cx={shape.newsX} cy={chartHeight + lane / 2} r={2.5} fill={theme.ink} />}
    </Svg>
  ) : null;
  if (width != null) return svg ?? <Svg width={width} height={height} />;
  return <View style={{ height }} onLayout={event => setMeasured(Math.floor(event.nativeEvent.layout.width))}>{svg}</View>;
}
