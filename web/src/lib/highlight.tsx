import type { ReactNode } from 'react';

/** Markers the API puts around matched words (Unicode private-use characters, never in real text). */
export const MARK_START = '';
export const MARK_END = '';

export interface Segment { text: string; marked: boolean }

/**
 * Splits highlighted text into plain and marked runs. The text is rendered as text nodes, never as
 * HTML, so a dish name containing "<script>" cannot inject anything; unbalanced markers are dropped.
 */
export function segments(highlighted: string): Segment[] {
  const out: Segment[] = [];
  let marked = false;
  let buf = '';
  for (const ch of highlighted) {
    if (ch === MARK_START || ch === MARK_END) {
      if (buf) out.push({ text: buf, marked });
      buf = '';
      marked = ch === MARK_START;
    } else {
      buf += ch;
    }
  }
  if (buf) out.push({ text: buf, marked });
  return out;
}

/** The highlighted version when there is one and it is the same text, otherwise the plain text. */
export function Marked({ text, highlighted }: { text: string; highlighted?: string }): ReactNode {
  if (!highlighted) return text;
  const parts = segments(highlighted);
  if (parts.map((p) => p.text).join('') !== text) return text; // stale or truncated: never show different words
  return parts.map((p, i) => (p.marked ? <mark key={i}>{p.text}</mark> : <span key={i}>{p.text}</span>));
}

/** Marks where the typed text occurs (case-insensitive), for names the API did not highlight, e.g. restaurants. */
export function markTyped(text: string, typed: string): string | undefined {
  const q = typed.trim().toLowerCase();
  if (!q) return undefined;
  const at = text.toLowerCase().indexOf(q);
  if (at < 0) return undefined;
  return text.slice(0, at) + MARK_START + text.slice(at, at + q.length) + MARK_END + text.slice(at + q.length);
}
