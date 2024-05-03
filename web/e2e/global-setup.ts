// A fresh stack is "healthy" before Elasticsearch has its index (search degrades to Postgres
// meanwhile, by design). Wait for the real index so search assertions are meaningful.
export default async function globalSetup() {
  const base = process.env.E2E_BASE_URL ?? 'http://localhost:5173';
  const deadline = Date.now() + 120_000;
  let streak = 0; // several in a row, so every API replica behind nginx is serving from the index
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${base}/api/search?q=mpdf&size=1`);
      streak = r.ok && (await r.json()).degraded === false ? streak + 1 : 0;
      if (streak >= 8) return;
    } catch { streak = 0; }
    await new Promise((res) => setTimeout(res, 250));
  }
  throw new Error('search index never became ready');
}
