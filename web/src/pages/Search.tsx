import { Link, useSearchParams } from 'react-router';
import { useFetch } from '../lib/useFetch';
import { useI18n } from '../lib/i18n';
import { money } from '../lib/format';
import type { DishHit, SearchResult, SearchSort, TrendingDish } from '../lib/types';
import { ErrorBox, Loading, Names, Spice, Tags } from '../components/bits';
import { SearchCombobox } from '../components/SearchCombobox';
import { Marked } from '../lib/highlight';
import { CUISINES } from './Home';
import { Photo } from '../components/DishImage';
import { km, useDeliverTo } from '../lib/location';

const TAGS = ['vegetarian', 'vegan', 'halal', 'gluten-free', 'spicy', 'signature', 'noodles', 'dumplings', 'dim-sum', 'seafood', 'soup', 'congee'];

export function Search() {
  const { t, label } = useI18n();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const tags = params.getAll('tag');
  const cuisine = params.get('cuisine') ?? '';
  const maxSpice = params.get('maxSpice') ?? '';
  const { deliverTo } = useDeliverTo();
  // distance only makes sense with a delivery point; an old link with sort=distance falls back to best match
  const sort = ((s) => (s === 'distance' && !deliverTo ? 'relevance' : s))((params.get('sort') ?? 'relevance') as SearchSort);
  const query = new URLSearchParams(params);
  query.set('size', '30');
  if (sort === 'relevance') query.delete('sort'); else query.set('sort', sort);
  if (deliverTo) { // only dishes from kitchens that deliver to the chosen place
    query.set('lat', deliverTo.lat.toFixed(4));
    query.set('lng', deliverTo.lng.toFixed(4));
  }
  const res = useFetch<SearchResult>(`/api/search?${query.toString()}`);
  const nothing = res.data !== null && !res.loading && res.data.hits.length === 0;
  const popular = useFetch<TrendingDish[]>(nothing ? '/api/trending?limit=6' : null);
  const counts = new Map((res.data?.facets.tags ?? []).map((f) => [f.value, f.count]));

  const update = (fn: (p: URLSearchParams) => void) => {
    const next = new URLSearchParams(params);
    fn(next);
    setParams(next, { replace: true });
  };

  return (
    <div className="search-page">
      <h1>{t('search.title')}</h1>
      <SearchCombobox key={q} id="q" initial={q} label={t('search.label')} placeholder={t('search.label')} button={t('home.searchButton')}
        onSearch={(text) => update((p) => (text ? p.set('q', text) : p.delete('q')))} />

      <div className="search-layout">
        <aside aria-labelledby="filters">
          <h2 id="filters">{t('search.filters')}</h2>
          <fieldset>
            <legend>{t('search.tags')}</legend>
            {TAGS.map((tag) => (
              <label key={tag} className="check">
                <input type="checkbox" checked={tags.includes(tag)}
                  onChange={(e) => update((p) => {
                    const rest = p.getAll('tag').filter((x) => x !== tag);
                    p.delete('tag');
                    [...rest, ...(e.target.checked ? [tag] : [])].forEach((x) => p.append('tag', x));
                  })} />
                {label(tag)}{counts.has(tag) ? <span className="muted"> ({counts.get(tag)})</span> : null}
              </label>
            ))}
          </fieldset>
          <label htmlFor="cuisine">{t('search.cuisine')}</label>
          <select id="cuisine" value={cuisine} onChange={(e) => update((p) => (e.target.value ? p.set('cuisine', e.target.value) : p.delete('cuisine')))}>
            <option value="">{t('search.any')}</option>
            {CUISINES.map((c) => <option key={c} value={c}>{label(c)}</option>)}
          </select>
          <label htmlFor="spice">{t('search.maxSpice')}</label>
          <select id="spice" value={maxSpice} onChange={(e) => update((p) => (e.target.value ? p.set('maxSpice', e.target.value) : p.delete('maxSpice')))}>
            <option value="">{t('search.any')}</option>
            {[0, 1, 2, 3].map((n) => <option key={n} value={n}>{t(`search.spice${n}` as 'search.spice0')}</option>)}
          </select>
          {(tags.length > 0 || cuisine || maxSpice) && (
            <button type="button" className="link-button" onClick={() => setParams(q ? { q } : {}, { replace: true })}>{t('search.clear')}</button>
          )}
        </aside>

        <section aria-live="polite" aria-busy={res.loading && !res.error} className={res.loading && res.data ? 'stale' : undefined}>
          {res.error ? <ErrorBox error={res.error} retry={res.reload} /> : !res.data ? <Loading /> : (
            <>
              {res.data.degraded && <p className="notice">{t('search.degraded')}</p>}
              <div className="results-bar">
                <p className="muted">{t('search.results', { n: res.data.total })}{deliverTo ? ` · ${t('search.deliveringTo', { place: deliverTo.label })}` : ''}</p>
                <label className="sort">
                  {t('search.sort')}{' '}
                  <select value={sort} onChange={(e) => update((p) => (e.target.value === 'relevance' ? p.delete('sort') : p.set('sort', e.target.value)))}>
                    <option value="relevance">{t('search.sortRelevance')}</option>
                    <option value="price">{t('search.sortPrice')}</option>
                    <option value="rating">{t('search.sortRating')}</option>
                    {deliverTo && <option value="distance">{t('search.sortDistance')}</option>}
                  </select>
                </label>
              </div>
              {res.data.relaxed && <p className="notice">{t('search.relaxed', { q })}</p>}
              {res.data.hits.length === 0 ? (
                <>
                  <p>{t('search.none')}</p>
                  {res.data.withoutFilters ? (
                    <p><button type="button" className="link-button" onClick={() => setParams(q ? { q } : {}, { replace: true })}>
                      {t('search.withoutFilters', { n: res.data.withoutFilters })}</button></p>
                  ) : null}
                  {popular.data && popular.data.length > 0 && (
                    <section aria-labelledby="popular-instead">
                      <h2 id="popular-instead">{t('search.popular')}</h2>
                      <ul className="chips">
                        {popular.data.map((d) => (
                          <li key={d.dishId}><Link className="chip" to={`/r/${d.restaurantSlug}#dish-${d.dishId}`}><Names o={d} /></Link></li>
                        ))}
                      </ul>
                    </section>
                  )}
                </>
              ) : (
                <ul className="results">
                  {res.data.hits.map((h) => <Result key={h.dishId} h={h} />)}
                </ul>
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}

function Result({ h }: { h: DishHit }) {
  const { t, lang } = useI18n();
  const zhFirst = lang === 'zh';
  const first = zhFirst ? h.nameZh : h.name;
  const second = zhFirst ? h.name : h.nameZh;
  return (
    <li className="card dish-hit">
      <Photo base={h.image} sizes="7rem" className="thumb" />
      <div>
        <h3>
          <Link to={`/r/${h.restaurantSlug}#dish-${h.dishId}`}>
            <span className="names">
              <span lang={zhFirst ? 'zh-CN' : 'en'}><Marked text={first} highlighted={zhFirst ? h.highlight?.nameZh : h.highlight?.name} /></span>{' '}
              <span className="secondary" lang={zhFirst ? 'en' : 'zh-CN'}><Marked text={second} highlighted={zhFirst ? h.highlight?.name : h.highlight?.nameZh} /></span>
            </span>
          </Link> <Spice level={h.spiceLevel} />
        </h3>
        <p className="muted">{t('search.at', { name: h.restaurantName })} · {money(h.priceCents)}{h.distanceM !== undefined ? ` · ${km(h.distanceM)}` : ''}</p>
        <p><Marked text={h.description} highlighted={h.highlight?.description} /></p>
        <Tags tags={h.tags} />
      </div>
    </li>
  );
}
