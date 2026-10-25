import { useEffect, useRef } from "react";
import { prefersReducedMotion } from "../lib/motion";
import { won } from "../market/format";

interface Props {
  value: number;
  /** 값을 문자열로 바꾸는 포맷터. 기본은 원화 표기. */
  format?: (value: number) => string;
  className?: string;
}

/**
 * 값이 바뀔 때만 배경과 글자색이 함께 번쩍인다. 상승·하락을 색으로 알리는
 * 피드백이며 장식용 루프가 아니다. 감소 모드에서는 애니메이션 없이 값만
 * 갱신되고 최종 상태는 같다.
 */
export function PriceCell({ value, format = won, className }: Props) {
  const ref = useRef<HTMLSpanElement>(null);
  const previous = useRef(value);
  const text = format(value);

  useEffect(() => {
    const element = ref.current;
    const before = previous.current;
    previous.current = value;
    if (!element || before === value || prefersReducedMotion()) return;
    const up = value > before;
    const styles = getComputedStyle(element);
    const read = (name: string, fallback: string) => styles.getPropertyValue(name).trim() || fallback;
    element.animate(
      [
        {
          color: read(up ? "--tick-up" : "--tick-down", styles.color),
          backgroundColor: read(up ? "--tick-up-bg" : "--tick-down-bg", "transparent"),
        },
        { color: styles.color, backgroundColor: "transparent" },
      ],
      { duration: 460, easing: "ease-out" },
    );
  }, [value]);

  const name = className ? `price ${className}` : "price";
  return <span ref={ref} className={name}>{text}</span>;
}
