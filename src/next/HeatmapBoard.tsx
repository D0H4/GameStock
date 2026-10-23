import { Link } from "../components/Link";
import { useNarrow, useWidth } from "../lib/useWidth";
import { rate, won } from "../market/format";
import { sessionRate } from "../market/selectors";
import { LISTING_BY_CODE } from "../market/universe";
import { useSteadySnapshot } from "../market/useSteadySnapshot";
import { MARKET_PATH } from "../router";

type Box = { x: number; y: number; w: number; h: number };

/** 시가총액 비중이 이보다 작아도 이 만큼의 면적은 준다. 그보다 작으면 등락률 글자가 칸 밖으로 넘친다. */
const MIN_SHARE = 0.025;
/** 지도가 좁으면 비율만으로는 칸이 너무 작아진다. 어느 칸이든 이 크기의 정사각형만큼 면적은 갖게 한다. */
const MIN_TILE = 92;

/** 가로세로 비가 가장 나쁜 칸. 줄에 칸을 더할지 정하는 기준이다. */
function worst(row: number[], side: number): number {
  const sum = row.reduce((total, area) => total + area, 0);
  return Math.max((side * side * Math.max(...row)) / (sum * sum), (sum * sum) / (side * side * Math.min(...row)));
}

/** 정사각형에 가까운 칸으로 나누는 트리맵(squarified). 면적이 큰 순서로 받아야 한다. */
function squarify(areas: number[], frame: Box): Box[] {
  const boxes: Box[] = [];
  let rest = { ...frame };
  let row: number[] = [];
  const place = () => {
    const sum = row.reduce((total, area) => total + area, 0);
    if (rest.w >= rest.h) {
      const width = sum / rest.h;
      let y = rest.y;
      for (const area of row) {
        boxes.push({ x: rest.x, y, w: width, h: area / width });
        y += area / width;
      }
      rest = { x: rest.x + width, y: rest.y, w: rest.w - width, h: rest.h };
    } else {
      const height = sum / rest.w;
      let x = rest.x;
      for (const area of row) {
        boxes.push({ x, y: rest.y, w: area / height, h: height });
        x += area / height;
      }
      rest = { x: rest.x, y: rest.y + height, w: rest.w, h: rest.h - height };
    }
    row = [];
  };
  for (let index = 0; index < areas.length;) {
    const side = Math.min(rest.w, rest.h);
    const next = [...row, areas[index]];
    if (row.length === 0 || worst(next, side) <= worst(row, side)) {
      row = next;
      index += 1;
    } else {
      place();
    }
  }
  if (row.length) place();
  return boxes;
}

/** 칸 면적은 시가총액, 색 농도는 등락 크기. 화려함이 곧 정보가 되는 자리. */
function Heatmap() {
  const snapshot = useSteadySnapshot();
  const [frame, width] = useWidth<HTMLDivElement>();
  const height = useNarrow() ? 640 : 560;
  const items = snapshot.codes
    .map((code) => ({ code, quote: snapshot.quotes[code], listing: LISTING_BY_CODE[code] }))
    .filter((item) => item.quote && item.listing)
    .sort((a, b) => (b.quote.marketCap ?? 0) - (a.quote.marketCap ?? 0));
  const total = items.reduce((sum, item) => sum + (item.quote.marketCap ?? 0), 0) || 1;
  const minShare = width > 0 ? Math.max(MIN_SHARE, (MIN_TILE * MIN_TILE) / (width * height)) : MIN_SHARE;
  const weights = items.map((item) => Math.max(minShare, (item.quote.marketCap ?? 0) / total));
  const weightSum = weights.reduce((sum, weight) => sum + weight, 0);
  const boxes = width > 0 ? squarify(weights.map((weight) => (weight / weightSum) * width * height), { x: 0, y: 0, w: width, h: height }) : [];

  return (
    <div className="nx-heat-frame" ref={frame} style={{ height }}>
      {items.length === 0 && <p className="nx-heat-status" role="status">시세를 불러오고 있어요.</p>}
      <ul className="nx-heat" aria-label="종목별 등락 지도. 칸 크기는 시가총액">
        {boxes.length > 0 && items.map(({ code, quote, listing }, index) => {
          const box = boxes[index];
          const ratio = sessionRate(quote);
          // 칸에 찍히는 소수 둘째 자리 값으로 나눠야 "0.00%" 칸이 색을 띠지 않는다.
          const percent = Number((ratio * 100).toFixed(2));
          const size = Math.abs(percent);
          const level = size === 0 ? 0 : size < 1 ? 1 : size < 3 ? 2 : size < 5 ? 3 : 4;
          const tone = percent > 0 ? "up" : percent < 0 ? "down" : "flat";
          return (
            <li key={code} style={{ left: box.x, top: box.y, width: box.w, height: box.h }}>
              <Link to={MARKET_PATH(code)} className={`nx-heat-tile is-${tone} is-l${level}`}
                aria-label={`${listing.name} ${rate(ratio)} ${won(quote.price)}${quote.restriction ? ` ${quote.restriction.kind === "CIRCUIT_BREAKER" ? "서킷브레이커" : "VI"}` : ""}`}>
                <span className="nx-heat-name">{listing.name}</span>
                <span className="nx-heat-rate nx-num">{rate(ratio)}</span>
                <span className="nx-heat-price nx-num">{won(quote.price)}</span>
                {quote.restriction && <span className="nx-heat-guard">{quote.restriction.kind === "CIRCUIT_BREAKER" ? "서킷브레이커" : "VI"}</span>}
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

export function HeatmapBoard() {
  return (
    <div className="nx-screen nx-heatmap-screen">
      <header className="nx-screen-head">
        <div>
          {/* 화면 이름은 상단 메뉴가 이미 보여 준다. 제목은 화면 낭독기용으로만 둔다. */}
          <h1 className="vh">히트맵</h1>
          <p className="nx-screen-sub">칸 크기는 시가총액, 색이 짙을수록 많이 움직였어요. 5초마다 새로 그려요.</p>
        </div>
      </header>
      <section className="nx-panel nx-heatmap-panel" aria-label="시장 지도">
        <Heatmap />
      </section>
    </div>
  );
}
