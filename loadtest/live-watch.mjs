// Live-status check: N customers each watch their order over SSE (through nginx, so streams land
// on either API replica) while the order is driven from PENDING_PAYMENT to DELIVERED by requests
// that also land on either replica. Reports server-change -> client-receive latency and verifies
// that no client ever sees a version go backwards and every client ends on the database's state.
//
//   node loadtest/live-watch.mjs [N]      (stack started with SEED_LOADTEST_USERS >= 1000 + N)
//   PAUSE_MS=500 node loadtest/live-watch.mjs 5   (steps half a second apart)
const BASE = process.env.BASE_URL ?? 'http://localhost:8000';
const PAY = process.env.MOCKPAY_URL ?? 'http://localhost:8090';
const PAY_KEY = process.env.MOCKPAY_SECRET_KEY ?? 'sk_test_wokflow_local';
const N = Number(process.argv[2] ?? 200);
// PAUSE_MS: wait between an order's steps. 0 (default) fires them back to back, which is harsher than
// a real kitchen and rider: each event then waits for the previous one to be published (per-order order).
const PAUSE = Number(process.env.PAUSE_MS ?? 0);

const post = (url, token, body, headers = {}) => fetch(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
  body: body === undefined ? undefined : JSON.stringify(body),
});
const signIn = async (email) => (await (await post(`${BASE}/api/auth/signin`, null, { email, password: 'wokflow-demo' })).json()).accessToken;
const pct = (xs, p) => { const s = [...xs].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };

function watch(orderId, token, state) {
  const ctl = new AbortController();
  const done = (async () => {
    const res = await fetch(`${BASE}/api/orders/${orderId}/events`, { headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' }, signal: ctl.signal });
    const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
    let buf = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) return;
      buf += value;
      let i;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, i); buf = buf.slice(i + 2);
        const name = /^event:(.*)$/m.exec(block)?.[1]?.trim();
        const data = /^data:(.*)$/m.exec(block)?.[1];
        if (!name || !data) continue;
        const e = JSON.parse(data);
        if (name === 'snapshot') { state.version = Math.max(state.version, e.version); state.connected(); continue; }
        if (name !== 'status') continue;
        if (e.version <= state.version) { state.duplicatesOrOld++; continue; } // a client ignores these
        if (e.version < state.lastSeen) state.regressions++;
        state.lastSeen = e.version;
        state.version = e.version;
        state.status = e.status;
        state.latencies.push(Date.now() - Date.parse(e.at));
        if (e.status === 'DELIVERED') { ctl.abort(); return; }
      }
    }
  })().catch(() => {});
  return { done, stop: () => ctl.abort() };
}

const merchant = await signIn('merchant@example.com');
const riders = await Promise.all(['rider@example.com', 'rider2@example.com'].map(signIn));
const menu = await (await fetch(`${BASE}/api/restaurants/midnight-congee`)).json();
const dish = menu.categories.flatMap((c) => c.dishes).find((d) => !d.limited && d.available);
const customers = [];
for (let i = 1; i <= N; i++) customers.push(await signIn(`loadtest+${1000 + i}@example.com`));
const addressIds = await Promise.all(customers.map(async (t) =>
  (await (await fetch(`${BASE}/api/me/addresses`, { headers: { Authorization: `Bearer ${t}` } })).json())[0].id));

const orders = await Promise.all(customers.map(async (t, i) => {
  for (let attempt = 0; ; attempt++) {
    const r = await post(`${BASE}/api/orders`, t, { restaurantId: menu.restaurant.id, items: [{ dishId: dish.id, quantity: 1 }], addressId: addressIds[i] }, { 'Idempotency-Key': `live-${Date.now()}-${i}` });
    if (r.status === 201) return { token: t, order: await r.json() };
    if (r.status !== 503 || attempt > 5) throw new Error(`place ${r.status} ${await r.text()}`);
    await new Promise((res) => setTimeout(res, 1000));
  }
}));

const states = orders.map(() => ({ version: 0, lastSeen: 0, status: null, latencies: [], regressions: 0, duplicatesOrOld: 0 }));
await Promise.all(states.map((s) => new Promise((res) => { s.connected = res; })).map((p, i) => {
  states[i].watcher = watch(orders[i].order.id, orders[i].token, states[i]);
  return p;
}));
console.log(`${N} streams connected`);

const t0 = Date.now();
await Promise.all(orders.map(async ({ order }, i) => {
  const step = async (url, token) => {
    if (PAUSE) await new Promise((res) => setTimeout(res, PAUSE));
    for (let attempt = 0; attempt < 10; attempt++) {
      const r = await post(url, token);
      if (r.ok) return;
      if (r.status !== 503) throw new Error(`${url} ${r.status} ${await r.text()}`);
      await new Promise((res) => setTimeout(res, 1000));
    }
  };
  const intent = order.checkoutUrl.split('/').pop();
  await post(`${PAY}/v1/payment_intents/${intent}/confirm`, null, { outcome: 'succeed' }, { Authorization: `Bearer ${PAY_KEY}` });
  while (states[i].version < 2) await new Promise((res) => setTimeout(res, 50)); // the stream says PAID
  await step(`${BASE}/api/merchant/orders/${order.id}/accept`, merchant);
  await step(`${BASE}/api/merchant/orders/${order.id}/ready`, merchant);
  const rider = riders[i % 2];
  for (const a of ['claim', 'pickup', 'deliver']) await step(`${BASE}/api/rider/orders/${order.id}/${a}`, rider);
}));
await Promise.race([Promise.all(states.map((s) => s.watcher.done)), new Promise((r) => setTimeout(r, 30000))]);
states.forEach((s) => s.watcher.stop());

const all = states.flatMap((s) => s.latencies);
const endedDelivered = states.filter((s) => s.status === 'DELIVERED' && s.version === 7).length;
console.log(JSON.stringify({
  streams: N,
  driveSeconds: ((Date.now() - t0) / 1000).toFixed(1),
  eventsReceived: all.length,
  expectedEvents: N * 6,
  latencyMs: { p50: pct(all, 0.5), p95: pct(all, 0.95), p99: pct(all, 0.99), max: Math.max(...all) },
  clientsEndingOnDelivered: endedDelivered,
  versionRegressions: states.reduce((n, s) => n + s.regressions, 0),
  duplicateOrOlderEventsIgnored: states.reduce((n, s) => n + s.duplicatesOrOld, 0),
}, null, 2));
process.exit(endedDelivered === N ? 0 : 1);
