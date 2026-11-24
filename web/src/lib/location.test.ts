import { describe, expect, it } from 'vitest';
import { km } from './location';

describe('km', () => {
  it('shows short distances in rounded metres and longer ones in km', () => {
    expect(km(40)).toBe('100 m');
    expect(km(449)).toBe('400 m');
    expect(km(949)).toBe('900 m');
    expect(km(950)).toBe('1.0 km');
    expect(km(1049)).toBe('1.0 km');
    expect(km(1050)).toBe('1.1 km');
    expect(km(2340)).toBe('2.3 km');
  });
});
