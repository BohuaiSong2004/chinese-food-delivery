// Peak-hour burst: N customers place orders at the same instant, then every order is paid and
// carried through the kitchen and a rider to the door, all concurrently.
//
//   docker run --rm --network host -v "$PWD/loadtest:/lt" grafana/k6:2.4.0 run -e VUS=600 /lt/peak.js          (all at once)
//   docker run --rm --network host -v "$PWD/loadtest:/lt" grafana/k6:2.4.0 run -e VUS=1000 -e RATE=10 /lt/peak.js (10 orders/s for 60 s)
//
// Needs the stack started with SEED_LOADTEST_USERS >= VUS and RATE_LIMIT_ENABLED=false (one
// machine is every "customer", so per-IP limits would only measure the limiter). Run prepare.sh first.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://localhost:8000';
const PAY = __ENV.MOCKPAY_URL || 'http://localhost:8090';
const PAY_KEY = __ENV.MOCKPAY_SECRET_KEY || 'sk_test_wokflow_local';
const VUS = Number(__ENV.VUS || 600);

const placeLatency = new Trend('order_place_ms', true);
const transitionLatency = new Trend('transition_ms', true);
const placed = new Counter('orders_placed');
const soldOut = new Counter('orders_sold_out');
const delivered = new Counter('orders_delivered');
const staleReads = new Counter('stale_reads');
const unexpected = new Counter('unexpected_responses');
const retried = new Counter('retries_after_503');

export const options = {
  setupTimeout: '10m',
  scenarios: __ENV.RATE
    // steady rush: RATE new orders per second for 60 s
    ? { steady: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE), timeUnit: '1s', duration: '60s', preAllocatedVUs: VUS, maxVUs: VUS } }
    // everyone at once
    : { burst: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '10m' } },
  thresholds: {
    unexpected_responses: ['count==0'],
    stale_reads: ['count==0'],
    http_req_failed: [{ threshold: 'rate<0.25', abortOnFail: false }], // 409 sold_out counts as "failed" to k6
  },
};

const json = { headers: { 'Content-Type': 'application/json' } };
const auth = (t, extra = {}) => ({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${t}`, ...extra } });

function signIn(email) {
  const r = http.post(`${BASE}/api/auth/signin`, JSON.stringify({ email, password: 'wokflow-demo' }), json);
  if (r.status !== 200) throw new Error(`sign in ${email}: ${r.status} ${r.body}`);
  return r.json('accessToken');
}

export function setup() {
  const merchant = signIn('merchant@example.com');
  const riders = ['rider@example.com', 'rider2@example.com'].map(signIn);
  const restaurants = ['lucky-star-dumplings', 'midnight-congee'].map((slug) => {
    const m = http.get(`${BASE}/api/restaurants/${slug}`).json();
    const dishes = m.categories.flatMap((c) => c.dishes).filter((d) => d.available);
    return { id: m.restaurant.id, slug, dishes: dishes.map((d) => ({ id: d.id, limited: d.limited })) };
  });
  // prepare.sh set the scarce dish (Shrimp & Pork Soup Dumplings) to exactly LIMITED_STOCK baskets.
  const scarceId = http.get(`${BASE}/api/search?q=${encodeURIComponent('虾仁鲜肉小笼包')}&size=1`).json().hits[0].dishId;
  const customers = [];
  for (let i = 1; i <= VUS; i++) {
    const token = signIn(`loadtest+${i}@example.com`);
    // the seeder gives every load-test customer a saved address in Alhambra (in range of both kitchens)
    const addressId = http.get(`${BASE}/api/me/addresses`, auth(token)).json()[0].id;
    customers.push({ token, addressId });
  }
  const before = http.get(`${BASE}/api/admin/stats`, auth(signIn('admin@example.com'))).json();
  return { merchant, riders, restaurants, scarceId, customers, deadlocksBefore: before.deadlocks };
}

/** After a successful change, an immediate read must show at least that version. */
function verifyRead(token, id, version) {
  const r = http.get(`${BASE}/api/orders/${id}`, auth(token));
  if (r.status === 200 && r.json('version') < version) staleReads.add(1);
}

function transition(token, url, version, expect = 200) {
  const started = Date.now();
  let r;
  for (let attempt = 0; attempt < 6; attempt++) {
    // safe to resend: If-Match makes a duplicate fail with 409 instead of acting twice
    r = http.post(url, null, auth(token, { 'If-Match': `"${version}"` }));
    if (r.status !== 503) break;
    retried.add(1);
    sleep(Number(r.headers['Retry-After'] || 1));
  }
  transitionLatency.add(Date.now() - started);
  if (r.status !== expect) {
    unexpected.add(1);
    console.error(`${url} -> ${r.status} ${r.body}`);
    return null;
  }
  return r.json();
}

export default function (data) {
  const i = (__ENV.RATE ? exec.scenario.iterationInTest : exec.vu.idInTest - 1) % data.customers.length;
  const { token: customer, addressId } = data.customers[i];
  const r = data.restaurants[i % 2];
  const items = [];
  if (i % 2 === 0) items.push({ dishId: data.scarceId, quantity: 1 }); // half the crowd wants the scarce dish
  const others = r.dishes.filter((d) => d.id !== data.scarceId);
  for (let k = 0; k < 2; k++) items.push({ dishId: others[(i * 7 + k * 3) % others.length].id, quantity: 1 + (k % 2) });
  const merged = Object.values(items.reduce((m, it) => { m[it.dishId] = { dishId: it.dishId, quantity: (m[it.dishId]?.quantity || 0) + it.quantity }; return m; }, {}));

  // A well-behaved client: on 503 it waits Retry-After and resends with the SAME Idempotency-Key,
  // so a retry can never create a second order.
  const key = `peak-${Date.now()}-${i}`;
  const body = JSON.stringify({ restaurantId: r.id, items: merged, addressId });
  const started = Date.now();
  let res;
  for (let attempt = 0; attempt < 6; attempt++) {
    res = http.post(`${BASE}/api/orders`, body, auth(customer, { 'Idempotency-Key': key }));
    if (res.status !== 503) break;
    retried.add(1);
    sleep(Number(res.headers['Retry-After'] || 1));
  }
  placeLatency.add(Date.now() - started); // includes any waiting and retries
  if (res.status === 409 && res.json('code') === 'sold_out') { soldOut.add(1); return; }
  if (!check(res, { 'order placed': (x) => x.status === 201 || x.status === 200 })) { unexpected.add(1); console.error(`place -> ${res.status} ${res.body}`); return; }
  placed.add(1);
  const order = res.json();
  let checkoutUrl = order.checkoutUrl;
  for (let attempt = 0; !checkoutUrl && attempt < 6; attempt++) {
    // the provider was slow when the order was placed: ask again (idempotent on the order id)
    sleep(1);
    retried.add(1);
    const c = http.post(`${BASE}/api/orders/${order.id}/checkout`, null, auth(customer));
    if (c.status === 200) checkoutUrl = c.json('checkoutUrl');
  }
  if (!checkoutUrl) { unexpected.add(1); console.error(`no checkout for ${order.id}`); return; }
  const intent = checkoutUrl.split('/').pop();

  // The customer pays on the provider (test-mode confirm); the webhook makes it PAID.
  const t0 = Date.now();
  http.post(`${PAY}/v1/payment_intents/${intent}/confirm`, JSON.stringify({ outcome: 'succeed' }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${PAY_KEY}` }, tags: { name: 'provider-confirm' } });
  // The web app learns about PAID over SSE; here a slow poll just waits for it. The exact
  // provider-confirm -> PAID latency is computed afterwards from server timestamps (verify.sh).
  let o = null;
  while (Date.now() - t0 < 60000) {
    sleep(1);
    o = http.get(`${BASE}/api/orders/${order.id}`, auth(customer)).json();
    if (o.status === 'PAID') break;
  }
  if (!o || o.status !== 'PAID') { unexpected.add(1); console.error(`never paid ${order.id}`); return; }

  const rider = data.riders[i % 2];
  const steps = [
    [data.merchant, `${BASE}/api/merchant/orders/${order.id}/accept`],
    [data.merchant, `${BASE}/api/merchant/orders/${order.id}/ready`],
    [rider, `${BASE}/api/rider/orders/${order.id}/claim`],
    [rider, `${BASE}/api/rider/orders/${order.id}/pickup`],
    [rider, `${BASE}/api/rider/orders/${order.id}/deliver`],
  ];
  let version = o.version;
  for (const [token, url] of steps) {
    const after = transition(token, url, version);
    if (!after) return;
    version = after.version;
    verifyRead(customer, order.id, version);
  }
  delivered.add(1);
}

export function teardown(data) {
  const admin = signIn('admin@example.com');
  const stats = http.get(`${BASE}/api/admin/stats`, auth(admin)).json();
  console.log(`deadlocks during run: ${stats.deadlocks - data.deadlocksBefore}`);
  console.log(`audit: ${http.get(`${BASE}/api/admin/ledger/audit`, auth(admin)).body}`);
}
