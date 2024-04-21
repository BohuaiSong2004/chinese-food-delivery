import { Link, useSearchParams } from 'react-router';
import { useState } from 'react';
import { useFetch } from '../lib/useFetch';
import { useI18n } from '../lib/i18n';
import { money } from '../lib/format';
import type { SearchResult } from '../lib/types';
import { ErrorBox, Loading, Names, Spice, Tags } from '../components/bits';
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
  const query = new URLSearchParams(params);
  query.set('size', '30');
  if (deliverTo) { // only dishes from kitchens that deliver to the chosen place
    query.set('lat', deliverTo.lat.toFixed(4));
    query.set('lng', deliverTo.lng.toFixed(4));
  }
  const res = useFetch<SearchResult>(`/api/search?${query.toString()}`);
  const counts = new Map((res.data?.facets.tags ?? []).map((f) => [f.value, f.count]));

  const update = (fn: (p: URLSearchParams) => void) => {
    const next = new URLSearchParams(params);
    fn(next);
    setParams(next, { replace: true });
  };

  return (
    <div className="search-page">
      <h1>{t('search.title')}</h1>
      <SearchBox key={q} initial={q} onSearch={(text) => update((p) => (text ? p.set('q', text) : p.delete('q')))} />

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
              <p className="muted">{t('search.results', { n: res.data.total })}{deliverTo ? ` · ${t('search.deliveringTo', { place: deliverTo.label })}` : ''}</p>
              {res.data.hits.length === 0 ? <p>{t('search.none')}</p> : (
                <ul className="results">
                  {res.data.hits.map((h) => (
                    <li key={h.dishId} className="card dish-hit">
                      <Photo base={h.image} sizes="7rem" className="thumb" />
                      <div>
                        <h3><Link to={`/r/${h.restaurantSlug}#dish-${h.dishId}`}><Names o={h} /></Link> <Spice level={h.spiceLevel} /></h3>
                        <p className="muted">{t('search.at', { name: h.restaurantName })} · {money(h.priceCents)}{h.distanceM !== undefined ? ` · ${km(h.distanceM)}` : ''}</p>
                        <p>{h.description}</p>
                        <Tags tags={h.tags} />
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}

/** Keyed by the query in the URL, so back/forward resets the box without syncing state in an effect. */
function SearchBox({ initial, onSearch }: { initial: string; onSearch: (q: string) => void }) {
  const { t } = useI18n();
  const [text, setText] = useState(initial);
  return (
    <form role="search" className="search-form" method="get" action="/search"
      onSubmit={(e) => { e.preventDefault(); onSearch(text.trim()); }}>
      <label htmlFor="q" className="visually-hidden">{t('search.label')}</label>
      <input id="q" name="q" type="search" value={text} onChange={(e) => setText(e.target.value)} placeholder={t('search.label')} />
      <button type="submit">{t('home.searchButton')}</button>
    </form>
  );
}
