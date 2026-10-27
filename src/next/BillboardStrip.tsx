import { useBillboard } from "../lib/billboard";

type Props = {
  nickname: string;
  body: string;
  preview?: boolean;
};

/** 전광판 한 줄. 홈과 작성 화면의 미리보기가 같은 모양을 쓴다. */
export function BillboardLine({ nickname, body, preview = false }: Props) {
  return (
    <div className={`nx-billboard${preview ? " is-preview" : ""}`}>
      <p className="nx-billboard-line">
        <span className="nx-billboard-author">{nickname}</span>
        <span className={`nx-billboard-body${body ? "" : " is-placeholder"}`}>{body || "여기에 적은 문구가 보여요"}</span>
      </p>
    </div>
  );
}

/** 홈 상단 띠. 보이는 문구가 있을 때만 그려서, 비어 있거나 받아 오지 못하면 홈은 띠가 없던 모습 그대로다. */
export function HomeBillboard() {
  const { board } = useBillboard();
  const current = board?.current;
  if (!current) return null;

  return (
    <section className="nx-billboard-slot" aria-label="전광판">
      <BillboardLine nickname={current.nickname} body={current.body} />
    </section>
  );
}
