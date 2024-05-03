import { beforeEach, describe, expect, it } from 'vitest';
import { forgetSearches, MAX_RECENT, recentSearches, rememberSearch } from './recent';

describe('recent searches', () => {
  beforeEach(() => localStorage.clear());

  it('keeps the newest first, without duplicates, at most five', () => {
    for (const q of ['mapo', 'boba', 'xlb', 'dan dan', 'congee', 'wonton']) rememberSearch(q);
    expect(recentSearches()).toEqual(['wonton', 'congee', 'dan dan', 'xlb', 'boba']);
    rememberSearch('  XLB ');
    expect(recentSearches()).toEqual(['XLB', 'wonton', 'congee', 'dan dan', 'boba']);
    expect(recentSearches()).toHaveLength(MAX_RECENT);
  });

  it('ignores blanks and very long text, and can be cleared', () => {
    rememberSearch('   ');
    rememberSearch('x'.repeat(101));
    expect(recentSearches()).toEqual([]);
    rememberSearch('boba');
    forgetSearches();
    expect(recentSearches()).toEqual([]);
  });

  it('survives broken or missing storage', () => {
    localStorage.setItem('wf_recent_searches', '{not json');
    expect(recentSearches()).toEqual([]);
    localStorage.setItem('wf_recent_searches', JSON.stringify(['ok', 3, null]));
    expect(recentSearches()).toEqual(['ok']);
    const throwing = { getItem: () => { throw new Error('denied'); }, setItem: () => { throw new Error('full'); } };
    expect(recentSearches(throwing)).toEqual([]);
    expect(rememberSearch('boba', throwing)).toEqual(['boba']); // shown for this page, just not stored
  });
});
