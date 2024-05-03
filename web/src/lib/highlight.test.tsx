import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { MARK_END as E, MARK_START as S, Marked, markTyped, segments } from './highlight';

describe('highlight', () => {
  it('splits marked and plain runs', () => {
    expect(segments(`${S}Mapo${E} ${S}Tofu${E}`)).toEqual([
      { text: 'Mapo', marked: true }, { text: ' ', marked: false }, { text: 'Tofu', marked: true },
    ]);
    expect(segments('plain')).toEqual([{ text: 'plain', marked: false }]);
    expect(segments(`${E}odd${S}`)).toEqual([{ text: 'odd', marked: false }]); // unbalanced markers are dropped
  });

  it('renders <mark> as text, never HTML', () => {
    const { container } = render(<p><Marked text="<b>Tofu</b> soup" highlighted={`<b>${S}Tofu${E}</b> soup`} /></p>);
    expect(container.querySelector('mark')?.textContent).toBe('Tofu');
    expect(container.querySelector('b')).toBeNull();
    expect(container.textContent).toBe('<b>Tofu</b> soup');
  });

  it('falls back to the plain text when the highlight is for different words', () => {
    const { container } = render(<p><Marked text="Mapo Tofu" highlighted={`${S}Kung${E} Pao`} /></p>);
    expect(container.textContent).toBe('Mapo Tofu');
    expect(container.querySelector('mark')).toBeNull();
  });

  it('marks typed text case-insensitively, including Chinese', () => {
    expect(markTyped('Lucky Star', 'luc')).toBe(`${S}Luc${E}ky Star`);
    expect(markTyped('成都夜市', '夜市')).toBe(`成都${S}夜市${E}`);
    expect(markTyped('Lucky Star', 'cdys')).toBeUndefined();
    expect(markTyped('Lucky Star', '  ')).toBeUndefined();
  });
});
