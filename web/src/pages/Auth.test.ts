import { describe, expect, it } from 'vitest';
import { safeNext } from './Auth';

describe('safeNext', () => {
  it('keeps same-site paths and refuses anything that could leave the site', () => {
    expect(safeNext('/cart')).toBe('/cart');
    expect(safeNext('/orders/1?x=2')).toBe('/orders/1?x=2');
    for (const bad of [null, '', 'https://evil.example', '//evil.example', '/\\evil.example', '/%0a', 'javascript:alert(1)', '/\u0007']) {
      expect(safeNext(bad === '/%0a' ? '/\n' : bad), String(bad)).toBeNull();
    }
  });
});
