import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { useNavigate } from 'react-router';
import { api } from '../lib/api';
import { useI18n } from '../lib/i18n';
import { useDeliverTo } from '../lib/location';
import { Marked, markTyped } from '../lib/highlight';
import { forgetSearches, recentSearches, rememberSearch } from '../lib/recent';
import { money } from '../lib/format';
import type { DishHit, RestaurantSuggestion, Suggestions } from '../lib/types';

type Option =
  | { kind: 'recent'; id: string; query: string }
  | { kind: 'dish'; id: string; dish: DishHit }
  | { kind: 'restaurant'; id: string; restaurant: RestaurantSuggestion };

const DEBOUNCE_MS = 120;

/**
 * The search box with suggestions as you type (WAI-ARIA combobox with a listbox popup).
 * Typing shows matching dishes and restaurants (pinyin and initials work, as in search); an empty
 * box shows recent searches. Arrow keys move through the options, Enter opens the highlighted one
 * or searches for the text, Escape closes the list. Focus never leaves the input.
 */
export function SearchCombobox({ id, initial = '', label, placeholder, button, onSearch }: {
  id: string; initial?: string; label: string; placeholder?: string; button: string; onSearch: (q: string) => void;
}) {
  const { t, lang } = useI18n();
  const navigate = useNavigate();
  const { deliverTo } = useDeliverTo();
  const listId = useId();
  const [text, setText] = useState(initial);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [recent, setRecent] = useState<string[]>(recentSearches);
  const [found, setFound] = useState<{ q: string; s: Suggestions } | null>(null);
  const cache = useRef(new Map<string, Suggestions>());
  const composing = useRef(false);
  const typed = text.trim();
  const point = deliverTo ? `&lat=${deliverTo.lat.toFixed(4)}&lng=${deliverTo.lng.toFixed(4)}` : '';

  useEffect(() => {
    if (!typed || !open) return;
    const key = typed + point;
    const hit = cache.current.get(key);
    if (hit) { setFound({ q: typed, s: hit }); return; }
    const ctl = new AbortController();
    const timer = setTimeout(() => {
      api<Suggestions>(`/api/search/suggest?q=${encodeURIComponent(typed)}${point}`, { signal: ctl.signal })
        .then((s) => {
          if (cache.current.size > 100) cache.current.clear();
          cache.current.set(key, s);
          setFound({ q: typed, s });
        })
        .catch(() => { /* suggestions are optional: the search button still works */ });
    }, DEBOUNCE_MS);
    return () => { clearTimeout(timer); ctl.abort(); };
  }, [typed, point, open]);

  // Suggestions for an older prefix stay up while the new ones load, as long as they still fit the text.
  const current = found && typed && typed.toLowerCase().startsWith(found.q.toLowerCase()) ? found.s : null;
  const options = useMemo<Option[]>(() => {
    if (!typed) return recent.map((q, i) => ({ kind: 'recent', id: `${listId}-r${i}`, query: q }));
    if (!current) return [];
    return [
      ...current.dishes.map((d) => ({ kind: 'dish' as const, id: `${listId}-d${d.dishId}`, dish: d })),
      ...current.restaurants.map((r) => ({ kind: 'restaurant' as const, id: `${listId}-s${r.slug}`, restaurant: r })),
    ];
  }, [typed, recent, current, listId]);
  const expanded = open && options.length > 0;
  const activeOption = expanded && active >= 0 && active < options.length ? options[active] : undefined;

  const search = (q: string) => {
    setOpen(false);
    setActive(-1);
    if (q.trim()) setRecent(rememberSearch(q));
    onSearch(q.trim());
  };

  const choose = (o: Option) => {
    setOpen(false);
    setActive(-1);
    if (o.kind === 'recent') { setText(o.query); search(o.query); return; }
    if (typed) setRecent(rememberSearch(typed));
    if (o.kind === 'dish') navigate(`/r/${o.dish.restaurantSlug}#dish-${o.dish.dishId}`);
    else navigate(`/r/${o.restaurant.slug}`);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    // While a Chinese (or Japanese, Korean…) input method is composing, Enter confirms the candidate and the
    // arrow keys move through candidates: leave them to it. Safari reports keyCode 229 for these keys.
    if (e.nativeEvent.isComposing || e.keyCode === 229) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (!open) { setOpen(true); setActive(e.key === 'ArrowDown' ? 0 : options.length - 1); return; }
      if (!options.length) return;
      // -1 is the text itself: past the last option the highlight returns to it, then wraps
      setActive((i) => (e.key === 'ArrowDown' ? (i + 1 >= options.length ? -1 : i + 1) : (i - 1 < -1 ? options.length - 1 : i - 1)));
    } else if (e.key === 'Escape') {
      if (expanded) { e.preventDefault(); setOpen(false); setActive(-1); }
      else if (text) { e.preventDefault(); setText(''); }
    } else if (e.key === 'Enter' && activeOption && !composing.current) {
      e.preventDefault();
      choose(activeOption);
    }
  };

  const primary = (o: { name: string; nameZh: string }) => (lang === 'zh' && o.nameZh ? o.nameZh : o.name);
  const secondary = (o: { name: string; nameZh: string }) => (lang === 'zh' ? o.name : o.nameZh);
  const dishes = options.filter((o) => o.kind === 'dish');
  const places = options.filter((o) => o.kind === 'restaurant');

  const render = (o: Option) => {
    const selected = activeOption?.id === o.id;
    const common = {
      id: o.id, role: 'option', 'aria-selected': selected, className: `suggestion${selected ? ' active' : ''}`,
      // mousedown, not click, would blur the input first and close the list
      onMouseDown: (e: React.MouseEvent) => e.preventDefault(),
      onClick: () => choose(o),
      onMouseMove: () => setActive(options.indexOf(o)),
    } as const;
    if (o.kind === 'recent') return <div key={o.id} {...common}><span aria-hidden="true">↺ </span>{o.query}</div>;
    if (o.kind === 'dish') {
      const d = o.dish;
      const zhFirst = lang === 'zh' && d.nameZh;
      return (
        <div key={o.id} {...common}>
          <span className="suggestion-name">
            <Marked text={primary(d)} highlighted={zhFirst ? d.highlight?.nameZh : d.highlight?.name} />{' '}
            <span className="secondary"><Marked text={secondary(d)} highlighted={zhFirst ? d.highlight?.name : d.highlight?.nameZh} /></span>
          </span>
          <span className="muted"> · {lang === 'zh' && d.restaurantNameZh ? d.restaurantNameZh : d.restaurantName} · {money(d.priceCents)}</span>
        </div>
      );
    }
    const r = o.restaurant;
    return (
      <div key={o.id} {...common}>
        <span className="suggestion-name"><Marked text={primary(r)} highlighted={markTyped(primary(r), typed)} />{' '}
          <span className="secondary">{secondary(r)}</span></span>
      </div>
    );
  };

  return (
    <form role="search" className="search-form combobox" method="get" action="/search"
      onSubmit={(e) => { e.preventDefault(); if (!composing.current) search(text); }}>
      <label htmlFor={id} className="visually-hidden">{label}</label>
      <input id={id} name="q" type="search" value={text} placeholder={placeholder} autoComplete="off" spellCheck={false}
        role="combobox" aria-autocomplete="list" aria-expanded={expanded} aria-controls={listId}
        aria-activedescendant={activeOption?.id}
        onChange={(e) => { setText(e.target.value); setOpen(true); setActive(-1); }}
        onFocus={() => setOpen(true)}
        onBlur={() => { setOpen(false); setActive(-1); }}
        onKeyDown={onKeyDown}
        onCompositionStart={() => { composing.current = true; }}
        // Safari fires the Enter that confirmed the candidate after compositionend: clear on the next tick
        onCompositionEnd={() => { setTimeout(() => { composing.current = false; }, 0); }} />
      <button type="submit">{button}</button>
      <div id={listId} role="listbox" aria-label={t('suggest.label')} className="suggestions" hidden={!expanded}>
        {!typed && options.length > 0 && (
          <div role="group" aria-label={t('suggest.recent')}>
            <div className="suggestion-group" aria-hidden="true">
              {t('suggest.recent')}
              <button type="button" className="link-button" tabIndex={-1}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => { forgetSearches(); setRecent([]); }}>{t('suggest.clearRecent')}</button>
            </div>
            {options.map(render)}
          </div>
        )}
        {dishes.length > 0 && (
          <div role="group" aria-label={t('suggest.dishes')}>
            <div className="suggestion-group" aria-hidden="true">{t('suggest.dishes')}</div>
            {dishes.map(render)}
          </div>
        )}
        {places.length > 0 && (
          <div role="group" aria-label={t('suggest.restaurants')}>
            <div className="suggestion-group" aria-hidden="true">{t('suggest.restaurants')}</div>
            {places.map(render)}
          </div>
        )}
      </div>
      <p className="visually-hidden" role="status" aria-live="polite">
        {open && typed && current ? t('suggest.count', { n: options.length }) : ''}
      </p>
    </form>
  );
}
