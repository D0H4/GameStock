const number = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 });
const index = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const won = value => `${number.format(Math.round(Number(value) || 0))}원`;
export const price = value => number.format(Math.round(Number(value) || 0));
export const indexValue = value => index.format(Number(value) || 0);

/** 비율(0.0123)을 부호 있는 백분율로. 0은 부호 없이 보합으로 읽힌다. */
export const signedPercent = ratio => {
  const percent = (Number(ratio) || 0) * 100;
  const sign = percent > 0 ? '+' : percent < 0 ? '−' : '';
  return `${sign}${Math.abs(percent).toFixed(2)}%`;
};

export const signedWon = amount => {
  const value = Math.round(Number(amount) || 0);
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  return `${sign}${number.format(Math.abs(value))}원`;
};

export const relativeTime = (iso, now = Date.now()) => {
  const at = Date.parse(iso);
  if (!Number.isFinite(at)) return '';
  const minutes = Math.max(0, Math.round((now - at) / 60000));
  if (minutes < 1) return '방금';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.round(hours / 24)}일 전`;
};

/** RSS 제목 끝의 " - 매체명"은 이유 문장에서 뺀다. */
export const cleanTitle = title => String(title || '').replace(/\s+-\s+[^-]{1,30}$/, '').trim();
