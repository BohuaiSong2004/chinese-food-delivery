import { src, srcSet } from '../lib/images';

/**
 * A 4:3 photo with explicit dimensions (no layout shift), responsive sources and lazy loading.
 * The dish or restaurant name is always next to it in text, so the image itself is decorative.
 */
export function Photo({ base, sizes, className, eager = false }: { base?: string; sizes: string; className?: string; eager?: boolean }) {
  if (!base) return <div className={`photo placeholder ${className ?? ''}`} aria-hidden="true" />;
  return (
    <img className={`photo ${className ?? ''}`} src={src(base, 640)} srcSet={srcSet(base)} sizes={sizes} width={640} height={480}
      alt="" loading={eager ? 'eager' : 'lazy'} decoding="async" fetchPriority={eager ? 'high' : 'auto'} />
  );
}
