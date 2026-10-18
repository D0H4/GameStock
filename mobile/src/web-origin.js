export function isTrustedAppUrl(value, appUrl) {
  try {
    const target = new URL(value);
    const app = new URL(appUrl);
    return ['https:', 'http:'].includes(target.protocol) && !target.username && !target.password && target.origin === app.origin;
  } catch { return false; }
}

export function authInjection(payload, appUrl) {
  const origin = JSON.stringify(new URL(appUrl).origin);
  const detail = JSON.stringify(payload);
  // Check in the document too: a navigation can race the native URL callback.
  return `if (window.top === window && window.location.origin === ${origin}) { window.dispatchEvent(new CustomEvent('gamestock-native-auth', {detail:${detail}})); } true;`;
}
