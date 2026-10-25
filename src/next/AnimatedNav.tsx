import { useLayoutEffect, useRef, type HTMLAttributes } from "react";

type Position = { selection: string; left: number; top: number; width: number; height: number };
// Product pages remount when their route changes; groups given an animationId retain their indicator geometry.
const positions = new Map<string, Position>();
const same = (a: Position, b: Position) => a.selection === b.selection && a.left === b.left && a.top === b.top && a.width === b.width && a.height === b.height;

/**
 * 밑줄 탭·세그먼트처럼 하나를 고르는 묶음. 고른 칸(.is-active)의 표시를 한 장의 막대로 그려 칸 사이를 미끄러지게 한다.
 * fill은 세그먼트처럼 칸 전체를 칠하는 표시로, 막대 높이도 고른 칸에 맞춘다.
 */
export function AnimatedNav({ as: Tag = "nav", animationId, activeKey, className, fill = false, inset = 0, children, ...rest }:
  HTMLAttributes<HTMLElement> & { as?: "nav" | "div"; animationId?: string; activeKey: string; className: string; fill?: boolean; inset?: number }) {
  const nav = useRef<HTMLDivElement>(null);
  const indicator = useRef<HTMLSpanElement>(null);
  const animation = useRef<Animation | null>(null);
  const local = useRef<Position | undefined>(undefined);
  const observer = useRef<ResizeObserver | null>(null);
  const update = useRef<(animate: boolean) => void>(() => {});

  update.current = (animate: boolean) => {
    const container = nav.current!, marker = indicator.current!;
    const active = container.querySelector<HTMLElement>(":scope > .is-active");
    if (!active || !container.getClientRects().length) { marker.hidden = true; return; }
    const next = { selection: activeKey, left: active.offsetLeft + active.offsetWidth * inset, top: active.offsetTop,
      width: active.offsetWidth * (1 - inset * 2), height: active.offsetHeight };
    const previous = animationId ? positions.get(animationId) : local.current;
    if (!marker.hidden && previous && same(previous, next)) return;
    const running = animation.current?.playState === "running" ? marker.getBoundingClientRect() : null;
    const box = container.getBoundingClientRect();
    const origin = running ? {
      left: running.left - box.left - container.clientLeft + container.scrollLeft, width: running.width,
      top: running.top - box.top - container.clientTop + container.scrollTop, height: running.height,
    } : previous;
    animation.current?.cancel();
    marker.hidden = false;
    marker.style.left = `${next.left}px`;
    marker.style.width = `${next.width}px`;
    if (fill) { marker.style.top = `${next.top}px`; marker.style.height = `${next.height}px`; }
    if (animate && !matchMedia("(prefers-reduced-motion: reduce)").matches && origin && next.width > 0 && previous?.selection !== activeKey) {
      const vertical = fill && next.height > 0 ? ` translateY(${origin.top - next.top}px) scaleY(${origin.height / next.height})` : "";
      animation.current = marker.animate([
        { transform: `translateX(${origin.left - next.left}px)${vertical} scaleX(${origin.width / next.width})` },
        { transform: "none" },
      ], { duration: 180, easing: "cubic-bezier(.2,.8,.2,1)" });
    }
    if (animationId) positions.set(animationId, next); else local.current = next;
  };

  // 칸 글자 수(건수 표시 등)가 바뀌어도 막대가 따라가도록 그릴 때마다 다시 잰다. 고른 칸이 바뀔 때만 움직인다.
  useLayoutEffect(() => {
    update.current(true);
    for (const child of nav.current!.children) if (child !== indicator.current) observer.current?.observe(child);
  });

  useLayoutEffect(() => {
    const container = nav.current!;
    const resize = new ResizeObserver(() => update.current(false));
    resize.observe(container);
    for (const child of container.children) if (child !== indicator.current) resize.observe(child);
    observer.current = resize;
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
    const stopMotion = () => { if (reduced.matches) animation.current?.cancel(); };
    reduced.addEventListener("change", stopMotion);
    return () => { resize.disconnect(); observer.current = null; reduced.removeEventListener("change", stopMotion); };
  }, []);

  return <Tag ref={nav} {...rest} className={`${className} nx-animated-nav${fill ? " is-fill" : ""}`}>
    <span ref={indicator} className="nx-nav-indicator" aria-hidden="true" hidden />
    {children}
  </Tag>;
}
