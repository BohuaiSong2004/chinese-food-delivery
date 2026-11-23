import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { signIn } from './helpers';

async function audit(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

for (const path of ['/', '/search?q=noodles', '/r/midnight-congee', '/r/chengdu-night-market', '/signin', '/cart']) {
  test(`no WCAG 2.1 AA violations on ${path} @mobile`, async ({ page }) => {
    await page.goto(path);
    await expect(page.locator('main h1')).toBeVisible();
    await audit(page);
  });
}

test('no WCAG 2.1 AA violations in the kitchen and rider consoles', async ({ page }) => {
  await signIn(page, 'merchant@example.com');
  await page.goto('/kitchen');
  await expect(page.getByRole('tab', { name: 'Orders' })).toBeVisible();
  await audit(page);
  await page.getByRole('tab', { name: 'Menu' }).click();
  await expect(page.getByRole('table')).toBeVisible();
  await audit(page);
  await page.getByRole('tab', { name: 'Earnings' }).click();
  await expect(page.getByText('Available for payout')).toBeVisible();
  await audit(page);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await signIn(page, 'rider@example.com');
  await page.goto('/rider');
  await expect(page.getByRole('heading', { name: 'Open jobs' })).toBeVisible();
  await audit(page);
});
