/** 옆에 워드마크가 있으므로 이미지 자체는 장식으로 둔다. */
export function BrandMark({ size = 30 }: { size?: number }) {
  return (
    <img
      className="brand-mark"
      src="/brand/shuen-mark.png"
      width={size}
      height={size}
      alt=""
      decoding="async"
      fetchPriority="high"
    />
  );
}

/**
 * 마크와 '슈엔증권' 글자가 한 장에 든 로고. 글자색이 화면 밝기마다 달라서 두 장을 두고
 * html[data-nx-theme]에 맞춰 하나만 보인다(app.css).
 */
export function BrandLogo({ height = 22 }: { height?: number }) {
  const width = Math.round((height * 333) / 84);
  return (
    <span className="brand-logo" role="img" aria-label="슈엔증권">
      <img className="brand-logo-light" src="/brand/shuen-logo-light.png" width={width} height={height} alt="" decoding="async" fetchPriority="high" />
      <img className="brand-logo-dark" src="/brand/shuen-logo-dark.png" width={width} height={height} alt="" decoding="async" fetchPriority="high" />
    </span>
  );
}
