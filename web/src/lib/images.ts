/** Image URLs from the API are a base; the files are <base>-320/640/1024.jpg (4:3, content-addressed). */
export const WIDTHS = [320, 640, 1024] as const;

export function src(base: string, width: (typeof WIDTHS)[number] = 640) {
  return `${base}-${width}.jpg`;
}

export function srcSet(base: string) {
  return WIDTHS.map((w) => `${base}-${w}.jpg ${w}w`).join(', ');
}
