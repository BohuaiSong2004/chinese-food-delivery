import { describe, expect, it } from 'vitest';
import { readResetToken } from './Password';

describe('readResetToken', () => {
  it('reads the token from the fragment', () => {
    expect(readResetToken('#token=abcdefghijklmnopqrstuvwxyz_-0123456789ABCDE')).toBe('abcdefghijklmnopqrstuvwxyz_-0123456789ABCDE');
  });
  it('rejects missing, short or odd tokens', () => {
    expect(readResetToken('')).toBeNull();
    expect(readResetToken('#token=short')).toBeNull();
    expect(readResetToken('#token=' + 'a'.repeat(30) + '<script>')).toBeNull();
    expect(readResetToken('#other=' + 'a'.repeat(43))).toBeNull();
  });
});
