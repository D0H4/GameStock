import { useLayoutEffect, useRef, useState, type RefObject } from "react";

/** 요소의 안쪽 폭(px). 창 크기나 옆 칸이 바뀌면 다시 잰다. */
export function useWidth<T extends HTMLElement>(): [RefObject<T | null>, number] {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const node = ref.current;
    if (!node) return;
    setWidth(node.clientWidth);
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

/** 좁은 화면 여부. 그래프와 지도의 높이처럼 CSS만으로 못 정하는 값에 쓴다. */
export function useNarrow(query = "(max-width: 720px)"): boolean {
  const [narrow, setNarrow] = useState(() => typeof window !== "undefined" && window.matchMedia(query).matches);
  useLayoutEffect(() => {
    const media = window.matchMedia(query);
    const apply = () => setNarrow(media.matches);
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [query]);
  return narrow;
}
