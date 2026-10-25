import { DEFAULT_PROFILE_AVATAR } from "../lib/profileAvatars";
import "./RankingAvatar.css";

const frames: Record<number, string> = {
  1: "/ranking/medal-gold.png",
  2: "/ranking/medal-silver.png",
  3: "/ranking/medal-bronze.png",
};

/** Only podium finishers display an avatar; the frame overlays its circular crop. */
export function RankingAvatar({ rank, src }: { rank: number; src?: string | null }) {
  const frame = frames[rank];
  if (!frame) return null;

  return (
    <span className="ranking-medal-avatar" aria-hidden="true">
      <img className="ranking-medal-photo" src={src || DEFAULT_PROFILE_AVATAR} alt="" width={40} height={40}
        decoding="async" onError={(event) => {
          if (event.currentTarget.src.endsWith(DEFAULT_PROFILE_AVATAR)) return;
          event.currentTarget.src = DEFAULT_PROFILE_AVATAR;
        }} />
      <img className="ranking-medal-frame" src={frame} alt="" width={56} height={56} decoding="async" />
    </span>
  );
}
