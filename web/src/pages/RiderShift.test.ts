import { describe, expect, it } from 'vitest';
import { stepTowards } from './RiderShift';

const metres = (a: { lat: number; lng: number }, b: { lat: number; lng: number }) => {
  const dy = (b.lat - a.lat) * 111_320;
  const dx = (b.lng - a.lng) * 111_320 * Math.cos((a.lat * Math.PI) / 180);
  return Math.hypot(dx, dy);
};

describe('stepTowards (demo rider)', () => {
  const kitchen = { lat: 34.0953, lng: -118.127 };
  const door = { lat: 34.0866, lng: -118.1212 };

  it('moves exactly the step length towards the target', () => {
    const next = stepTowards(kitchen, door, 150);
    expect(metres(kitchen, next)).toBeCloseTo(150, 0);
    expect(metres(next, door)).toBeCloseTo(metres(kitchen, door) - 150, 0);
  });

  it('lands on the target instead of overshooting it', () => {
    const close = stepTowards(door, kitchen, 10_000);
    expect(close).toEqual(kitchen);
  });

  it('arrives after the expected number of steps', () => {
    let p = kitchen;
    let steps = 0;
    while ((p.lat !== door.lat || p.lng !== door.lng) && steps < 100) {
      p = stepTowards(p, door, 150);
      steps++;
    }
    expect(steps).toBe(Math.ceil(metres(kitchen, door) / 150));
  });
});
