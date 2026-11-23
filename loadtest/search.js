// Search latency under steady load: 20 clients for 30 s cycling through real queries.
//   docker run --rm --network host -v "$PWD/loadtest:/lt" grafana/k6:2.4.0 run /lt/search.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8000';
const QUERIES = ['mapo tofu', 'mpdf', 'dumplng', 'xiaolong', '牛肉面', 'niuroumian', 'noodles', 'congee', 'char siu',
  'lanzhou', 'doufu', 'soup dumplings', 'egg tart', '麻婆豆腐', 'beef', 'spicy chicken'];
export const options = { vus: 20, duration: '30s', summaryTrendStats: ['med', 'p(95)', 'p(99)'] };

export default function () {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const tag = Math.random() < 0.3 ? '&tag=vegetarian' : '';
  const r = http.get(`${BASE}/api/search?size=20&q=${encodeURIComponent(q)}${tag}`, { tags: { name: 'search' } });
  check(r, { ok: (x) => x.status === 200 });
}
