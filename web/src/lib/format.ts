const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
export const money = (cents: number) => usd.format(cents / 100);

export function time(iso: string, lang: string) {
  return new Intl.DateTimeFormat(lang === 'zh' ? 'zh-CN' : 'en-US', { hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
}

export function dateTime(iso: string, lang: string) {
  return new Intl.DateTimeFormat(lang === 'zh' ? 'zh-CN' : 'en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  }).format(new Date(iso));
}

export const shortId = (id: string) => id.slice(0, 8).toUpperCase();
