import { gameIcon } from "../market/universe";

export function GameIcon({
  code,
  name,
  size = "default",
}: {
  code: string;
  name?: string;
  size?: "small" | "default" | "large" | "xl" | "detail" | "table" | "news";
}) {
  const source = gameIcon(code);
  const className =
    size === "detail"
      ? "game-icon"
      : size === "table"
        ? "game-icon game-icon-table"
        : size === "news"
          ? "game-icon game-icon-news"
          : `game-icon is-${size}`;

  if (!source) return <span className={className} aria-hidden="true" />;

  return <img className={className} src={source} alt={name ? `${name} 앱 아이콘` : ""} aria-hidden={name ? undefined : true} loading="lazy" decoding="async" />;
}
