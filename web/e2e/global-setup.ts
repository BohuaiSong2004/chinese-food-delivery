// A fresh stack is "healthy" before Elasticsearch has its index (search degrades to Postgres
// meanwhile, by design). Wait for the real index so search assertions are meaningful.
export default async function globalSetup() {
  const base = process.env.E2E_BASE_URL ?? 'http://localhost:5173';
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${base}/api/search?q=mpdf&size=1`);
      if (r.ok && (await r.json()).degraded === false) return;
    } catch { /* not up yet */ }
    await new Promise((res) => setTimeout(res, 1000));
  }
  throw new Error('search index never became ready');
}
