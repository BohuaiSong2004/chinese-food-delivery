import { describe, expect, it } from 'vitest';
import { dictionaries } from './i18n';

describe('dictionaries', () => {
  it('Chinese has every English key, non-empty, with the same placeholders', () => {
    for (const [key, en] of Object.entries(dictionaries.en)) {
      const zh = dictionaries.zh[key as keyof typeof dictionaries.zh];
      expect(zh, key).toBeTruthy();
      const vars = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
      expect(vars(zh), key).toEqual(vars(en));
    }
  });
});
