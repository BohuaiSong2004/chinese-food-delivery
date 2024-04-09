// Suggestions while typing: 10 people typing at once, one request per keystroke prefix (the browser
// debounces at 120 ms, so this is the worst case: no typing pauses, no client cache).
//   docker run --rm --network host -v "$PWD/loadtest:/lt" grafana/k6:2.4.0 run /lt/suggest.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8000';
const WORDS = ['mapo tofu', 'mpdf', 'xiaolongbao', 'dumplings', '牛肉面', 'lucky star', 'cdys', 'boba', 'wonton', 'congee'];
export const options = { vus: 10, duration: '30s', summaryTrendStats: ['med', 'p(95)', 'p(99)'] };

export default function () {
  const word = WORDS[Math.floor(Math.random() * WORDS.length)];
  for (let i = 1; i <= word.length; i++) {
    const r = http.get(`${BASE}/api/search/suggest?q=${encodeURIComponent(word.slice(0, i))}`, { tags: { name: 'suggest' } });
    check(r, { ok: (x) => x.status === 200 });
    sleep(0.12); // a keystroke every 120 ms
  }
}
