import { useState } from 'react';
import { useFetch } from '../../lib/useFetch';
import { useI18n } from '../../lib/i18n';
import { api, ApiError, uploadFile } from '../../lib/api';
import { money } from '../../lib/format';
import { ErrorBox, Loading, Names } from '../../components/bits';
import { Photo } from '../../components/DishImage';

export interface MerchantDish {
  id: number; categoryId: number; name: string; nameZh: string; description: string; priceCents: number; tags: string[];
  spiceLevel: number; available: boolean; dailyStock?: number; stockRemaining?: number; version: number; imageKey?: string;
}
interface Category { id: number; name: string; nameZh: string; position: number; version: number }
interface MenuData { dishes: MerchantDish[]; categories: Category[] }

const DISH_TAGS = ['signature', 'spicy', 'vegetarian', 'vegan', 'halal', 'gluten-free', 'peanuts', 'seafood', 'noodles', 'dumplings',
  'dim-sum', 'soup', 'rice', 'congee', 'tofu', 'bbq', 'grill', 'dessert', 'drink', 'late-night'];

const message = (err: unknown) => (err instanceof ApiError ? Object.values(err.fields ?? {})[0] ?? err.message : String(err));

/** Sections and dishes: add, edit, reorder, archive, and dish photos. Every write is optimistic on the row version. */
export function MenuEditor({ rid }: { rid: number }) {
  const { t } = useI18n();
  const menu = useFetch<MenuData>(`/api/merchant/restaurants/${rid}/menu`);
  const [status, setStatus] = useState<string | null>(null);
  const [adding, setAdding] = useState<number | null>(null);
  const [editing, setEditing] = useState<number | null>(null);
  const [newSection, setNewSection] = useState({ name: '', nameZh: '' });

  if (menu.error) return <ErrorBox error={menu.error} retry={menu.reload} />;
  if (!menu.data) return <Loading />;
  const { categories, dishes } = menu.data;
  const run = async (work: () => Promise<unknown>, done: string) => {
    setStatus(null);
    try {
      await work();
      setStatus(done);
    } catch (err) {
      setStatus(err instanceof ApiError && err.code === 'stale_version' ? t('merchant.stale') : message(err));
    }
    menu.reload();
  };
  const move = (i: number, by: number) => {
    const ids = categories.map((c) => c.id);
    [ids[i], ids[i + by]] = [ids[i + by]!, ids[i]!];
    void run(() => api(`/api/merchant/restaurants/${rid}/categories/order`, { method: 'POST', body: { ids } }), t('menuEd.reordered'));
  };

  return (
    <div className="menu-editor">
      <p role="status" className="muted status-line">{status ?? ''}</p>
      {categories.map((c, i) => {
        const inSection = dishes.filter((d) => d.categoryId === c.id);
        return (
          <section key={c.id} className="menu-section" aria-labelledby={`sec-${c.id}`}>
            <SectionHeader c={c} count={inSection.length} first={i === 0} last={i === categories.length - 1}
              onMove={(by) => move(i, by)} run={run} />
            <ul className="dish-rows">
              {inSection.map((d) => (
                <li key={`${d.id}-${d.version}`} className="dish-row" data-dish={d.id}>
                  {editing === d.id ? (
                    <DishForm rid={rid} categories={categories} dish={d} onDone={(msg) => { setEditing(null); setStatus(msg); menu.reload(); }} />
                  ) : (
                    <>
                      <Photo base={d.imageKey ? `/api/images/${d.imageKey}` : undefined} sizes="6rem" className="thumb" />
                      <div className="dish-row-text">
                        <strong><Names o={d} /></strong>
                        <span className="muted">{money(d.priceCents)}{d.dailyStock ? ` · ${t('menuEd.stockLine', { left: d.stockRemaining ?? 0, of: d.dailyStock })}` : ''}</span>
                      </div>
                      <label className="check">
                        <input type="checkbox" checked={d.available} onChange={(e) => void run(() => api(`/api/merchant/dishes/${d.id}`, {
                          method: 'PATCH', body: { priceCents: d.priceCents, available: e.target.checked, dailyStock: d.dailyStock ?? null, version: d.version },
                        }), t('merchant.saved'))} />
                        {t('merchant.available')}<span className="visually-hidden"> — {d.name}</span>
                      </label>
                      <button type="button" className="secondary-button" onClick={() => setEditing(d.id)} aria-label={t('menuEd.editLabel', { name: d.name })}>{t('menuEd.edit')}</button>
                    </>
                  )}
                </li>
              ))}
            </ul>
            {adding === c.id
              ? <DishForm rid={rid} categories={categories} categoryId={c.id} onDone={(msg) => { setAdding(null); setStatus(msg); menu.reload(); }} />
              : <button type="button" className="link-button" onClick={() => setAdding(c.id)}>{t('menuEd.addDish', { section: c.name })}</button>}
          </section>
        );
      })}
      <form method="post" className="inline-form new-section" onSubmit={(e) => {
        e.preventDefault();
        void run(() => api(`/api/merchant/restaurants/${rid}/categories`, { method: 'POST', body: newSection }), t('menuEd.sectionAdded'))
          .then(() => setNewSection({ name: '', nameZh: '' }));
      }}>
        <h2 className="h3">{t('menuEd.newSection')}</h2>
        <label htmlFor="ns-name">{t('menuEd.sectionName')}</label>
        <input id="ns-name" required maxLength={60} value={newSection.name} onChange={(e) => setNewSection((s) => ({ ...s, name: e.target.value }))} />
        <label htmlFor="ns-name-zh">{t('menuEd.sectionNameZh')}</label>
        <input id="ns-name-zh" required maxLength={30} lang="zh-CN" value={newSection.nameZh} onChange={(e) => setNewSection((s) => ({ ...s, nameZh: e.target.value }))} />
        <button type="submit">{t('menuEd.addSection')}</button>
      </form>
    </div>
  );
}

function SectionHeader({ c, count, first, last, onMove, run }: {
  c: Category; count: number; first: boolean; last: boolean; onMove: (by: number) => void;
  run: (work: () => Promise<unknown>, done: string) => Promise<void>;
}) {
  const { t } = useI18n();
  const [renaming, setRenaming] = useState(false);
  const [name, setName] = useState(c.name);
  const [nameZh, setNameZh] = useState(c.nameZh);
  if (renaming) {
    return (
      <form method="post" className="inline-form" onSubmit={(e) => {
        e.preventDefault();
        void run(() => api(`/api/merchant/categories/${c.id}`, { method: 'PUT', body: { category: { name, nameZh }, version: c.version } }),
          t('merchant.saved')).then(() => setRenaming(false));
      }}>
        <label htmlFor={`rn-${c.id}`}>{t('menuEd.sectionName')}</label>
        <input id={`rn-${c.id}`} required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
        <label htmlFor={`rnz-${c.id}`}>{t('menuEd.sectionNameZh')}</label>
        <input id={`rnz-${c.id}`} required maxLength={30} lang="zh-CN" value={nameZh} onChange={(e) => setNameZh(e.target.value)} />
        <button type="submit">{t('merchant.save')}</button>
        <button type="button" className="secondary-button" onClick={() => setRenaming(false)}>{t('common.cancel')}</button>
      </form>
    );
  }
  return (
    <div className="section-head">
      <h2 id={`sec-${c.id}`} className="h3"><Names o={c} /> <span className="count">{count}</span></h2>
      <div className="actions">
        <button type="button" className="secondary-button icon" disabled={first} onClick={() => onMove(-1)} aria-label={t('menuEd.up', { name: c.name })}>↑</button>
        <button type="button" className="secondary-button icon" disabled={last} onClick={() => onMove(1)} aria-label={t('menuEd.down', { name: c.name })}>↓</button>
        <button type="button" className="secondary-button" onClick={() => setRenaming(true)} aria-label={t('menuEd.renameLabel', { name: c.name })}>{t('menuEd.rename')}</button>
        {count === 0 && (
          <button type="button" className="secondary-button" onClick={() => void run(() => api(`/api/merchant/categories/${c.id}`, { method: 'DELETE' }), t('menuEd.sectionRemoved'))}
            aria-label={t('menuEd.removeSectionLabel', { name: c.name })}>{t('menuEd.removeSection')}</button>
        )}
      </div>
    </div>
  );
}

function DishForm({ rid, categories, dish, categoryId, onDone }: {
  rid: number; categories: Category[]; dish?: MerchantDish; categoryId?: number; onDone: (message: string | null) => void;
}) {
  const { t, label } = useI18n();
  const [f, setF] = useState({
    categoryId: dish?.categoryId ?? categoryId ?? categories[0]!.id, name: dish?.name ?? '', nameZh: dish?.nameZh ?? '',
    description: dish?.description ?? '', price: dish ? (dish.priceCents / 100).toFixed(2) : '', tags: dish?.tags ?? [],
    spiceLevel: dish?.spiceLevel ?? 0, available: dish?.available ?? true, stock: dish?.dailyStock?.toString() ?? '',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const id = dish?.id ?? `new-${categoryId}`;
  const set = <K extends keyof typeof f>(k: K, v: (typeof f)[K]) => setF((x) => ({ ...x, [k]: v }));

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = { categoryId: f.categoryId, name: f.name, nameZh: f.nameZh, description: f.description,
      priceCents: Math.round(Number(f.price) * 100), tags: f.tags, spiceLevel: f.spiceLevel, available: f.available,
      dailyStock: f.stock ? Number(f.stock) : null };
    try {
      if (dish) await api(`/api/merchant/dishes/${dish.id}`, { method: 'PUT', body: { dish: body, version: dish.version } });
      else await api(`/api/merchant/restaurants/${rid}/dishes`, { method: 'POST', body });
      onDone(t('merchant.saved'));
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'stale_version' ? t('merchant.stale') : message(err));
      setBusy(false);
    }
  };
  const upload = async (file: File | undefined) => {
    if (!file || !dish) return;
    setBusy(true);
    setError(null);
    try {
      await uploadFile(`/api/merchant/dishes/${dish.id}/image`, file);
      onDone(t('menuEd.photoSaved'));
    } catch (err) {
      setError(message(err));
      setBusy(false);
    }
  };

  return (
    <form method="post" className="dish-form card" onSubmit={(e) => void save(e)} aria-label={dish ? t('menuEd.editLabel', { name: dish.name }) : t('menuEd.newDish')}>
      <div className="form-grid">
        <div><label htmlFor={`df-name-${id}`}>{t('menuEd.dishName')}</label>
          <input id={`df-name-${id}`} required maxLength={80} value={f.name} onChange={(e) => set('name', e.target.value)} /></div>
        <div><label htmlFor={`df-namezh-${id}`}>{t('menuEd.dishNameZh')}</label>
          <input id={`df-namezh-${id}`} required maxLength={40} lang="zh-CN" value={f.nameZh} onChange={(e) => set('nameZh', e.target.value)} /></div>
      </div>
      <label htmlFor={`df-desc-${id}`}>{t('menuEd.description')}</label>
      <textarea id={`df-desc-${id}`} maxLength={300} rows={2} value={f.description} onChange={(e) => set('description', e.target.value)} />
      <div className="form-grid">
        <div><label htmlFor={`df-price-${id}`}>{t('merchant.price')}</label>
          <input id={`df-price-${id}`} required inputMode="decimal" pattern="\d+(\.\d{1,2})?" value={f.price} onChange={(e) => set('price', e.target.value)} /></div>
        <div><label htmlFor={`df-stock-${id}`}>{t('merchant.dailyStock')}</label>
          <input id={`df-stock-${id}`} inputMode="numeric" pattern="\d*" value={f.stock} onChange={(e) => set('stock', e.target.value)} aria-describedby={`df-stock-hint-${id}`} />
          <p id={`df-stock-hint-${id}`} className="muted">{t('menuEd.stockHint')}</p></div>
        <div><label htmlFor={`df-spice-${id}`}>{t('menuEd.spice')}</label>
          <select id={`df-spice-${id}`} value={f.spiceLevel} onChange={(e) => set('spiceLevel', Number(e.target.value))}>
            {[0, 1, 2, 3].map((n) => <option key={n} value={n}>{t(`search.spice${n}` as 'search.spice0')}</option>)}
          </select></div>
        <div><label htmlFor={`df-cat-${id}`}>{t('menuEd.section')}</label>
          <select id={`df-cat-${id}`} value={f.categoryId} onChange={(e) => set('categoryId', Number(e.target.value))}>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select></div>
      </div>
      <fieldset>
        <legend>{t('search.tags')}</legend>
        <div className="check-grid">
          {DISH_TAGS.map((tag) => (
            <label key={tag} className="check">
              <input type="checkbox" checked={f.tags.includes(tag)} disabled={!f.tags.includes(tag) && f.tags.length >= 6}
                onChange={(e) => set('tags', e.target.checked ? [...f.tags, tag] : f.tags.filter((x) => x !== tag))} />
              {label(tag)}
            </label>
          ))}
        </div>
      </fieldset>
      <label className="check"><input type="checkbox" checked={f.available} onChange={(e) => set('available', e.target.checked)} /> {t('merchant.available')}</label>
      {dish && (
        <div className="photo-field">
          <Photo base={dish.imageKey ? `/api/images/${dish.imageKey}` : undefined} sizes="10rem" className="thumb large" />
          <div>
            <label htmlFor={`df-photo-${id}`}>{t('menuEd.photo')}</label>
            <input id={`df-photo-${id}`} type="file" accept="image/jpeg,image/png,image/webp" disabled={busy}
              onChange={(e) => void upload(e.target.files?.[0])} aria-describedby={`df-photo-hint-${id}`} />
            <p id={`df-photo-hint-${id}`} className="muted">{t('menuEd.photoHint')}</p>
            {dish.imageKey && <button type="button" className="link-button" disabled={busy} onClick={() => void api(`/api/merchant/dishes/${dish.id}/image`, { method: 'DELETE' }).then(() => onDone(t('menuEd.photoRemoved')))}>{t('menuEd.removePhoto')}</button>}
          </div>
        </div>
      )}
      {error && <p className="notice error" role="alert">{error}</p>}
      <div className="actions">
        <button type="submit" disabled={busy}>{dish ? t('merchant.save') : t('menuEd.create')}</button>
        <button type="button" className="secondary-button" onClick={() => onDone(null)}>{t('common.cancel')}</button>
        {dish && (
          <button type="button" className="secondary-button danger" disabled={busy}
            onClick={() => { if (window.confirm(t('menuEd.archiveConfirm', { name: dish.name }))) void api(`/api/merchant/dishes/${dish.id}`, { method: 'DELETE' }).then(() => onDone(t('menuEd.archived'))); }}>
            {t('menuEd.archive')}
          </button>
        )}
      </div>
    </form>
  );
}
