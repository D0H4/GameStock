import { useEffect, useRef, useState } from "react";
import { useBillboard, type BillboardShowing } from "../lib/billboard";

/** 여러 문구가 떠 있을 때 다음 문구로 넘어가는 간격. */
const ROTATE_MS = 10_000;

type Props = {
  nickname: string;
  body: string;
  preview?: boolean;
};

type TextProps = { nickname: string; body: string; className?: string; onAnimationEnd?: () => void };

function BillboardText({ nickname, body, className = "", onAnimationEnd }: TextProps) {
  return (
    <p className={`nx-billboard-line ${className}`} onAnimationEnd={onAnimationEnd}>
      <span className="nx-billboard-author">{nickname}</span>
      <span className={`nx-billboard-body${body ? "" : " is-placeholder"}`}>{body || "여기에 적은 문구가 보여요"}</span>
    </p>
  );
}

/** 전광판 한 줄. 홈과 작성 화면의 미리보기가 같은 모양을 쓴다. */
export function BillboardLine({ nickname, body, preview = false }: Props) {
  return (
    <div className={`nx-billboard${preview ? " is-preview" : ""}`}>
      <BillboardText nickname={nickname} body={body} />
    </div>
  );
}

const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/**
 * 홈 상단 띠. 보이는 문구가 있을 때만 그려서, 비어 있거나 받아 오지 못하면 홈은 띠가 없던 모습 그대로다.
 * 문구는 아래에서 올라오고, 둘 이상이면 10초마다 위로 밀려나며 다음 문구로 바뀐다. 마우스를 올리면 멈춘다.
 */
export function HomeBillboard() {
  const { board } = useBillboard();
  const live = board?.live ?? [];
  const [step, setStep] = useState(0);
  const [leaving, setLeaving] = useState<{ item: BillboardShowing; step: number } | null>(null);
  const [paused, setPaused] = useState(false);
  const shown = useRef<BillboardShowing | null>(null);
  const count = live.length;
  const current = count ? live[step % count] : null;
  shown.current = current;

  useEffect(() => {
    if (count < 2 || paused) return undefined;
    const timer = window.setInterval(() => {
      const outgoing = shown.current;
      if (outgoing && !reducedMotion()) setLeaving({ item: outgoing, step: Date.now() });
      setStep((value) => value + 1);
    }, ROTATE_MS);
    return () => window.clearInterval(timer);
  }, [count, paused]);

  if (!current) return null;

  return (
    <section className="nx-billboard-slot" aria-label="전광판" onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}>
      <div className="nx-billboard is-rotating">
        {leaving && (
          <BillboardText key={`out-${leaving.step}`} className="is-leaving" nickname={leaving.item.nickname} body={leaving.item.body}
            onAnimationEnd={() => setLeaving(null)} />
        )}
        <BillboardText key={`in-${step}-${current.id}`} className="is-entering" nickname={current.nickname} body={current.body} />
      </div>
    </section>
  );
}
