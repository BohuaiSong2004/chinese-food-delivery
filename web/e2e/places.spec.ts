import { expect, test } from '@playwright/test';
import { newCustomer } from './helpers';

test('a visitor sets a ZIP and sees fees, times and who delivers @mobile', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Set delivery location' }).click();
  await page.getByLabel('ZIP code').fill('91801');
  await page.getByRole('button', { name: 'Show restaurants that deliver here' }).click();
  await expect(page.getByRole('heading', { name: 'Restaurants delivering to 91801 Alhambra' })).toBeVisible();
  const xian = page.locator('.restaurant-card', { hasText: "Xi'an Spice Road" });
  await expect(xian).toContainText('Doesn’t deliver to you');
  await expect(page.locator('.restaurant-card', { hasText: 'Lucky Star Dumplings' })).toContainText(/\$2\.99 delivery · ~\d+ min/);

  await page.getByRole('button', { name: 'Deliver to: 91801 Alhambra' }).click();
  await page.getByLabel('ZIP code').fill('10001');
  await page.getByRole('button', { name: 'Show restaurants that deliver here' }).click();
  await expect(page.getByRole('alert')).toContainText('We don\'t deliver to 10001 yet');
});

test('a saved address in Rowland Heights changes which kitchens can take the order', async ({ page }) => {
  await newCustomer(page);
  await page.goto('/account');
  await page.getByRole('button', { name: 'Add an address' }).click();
  await page.getByLabel('Label (e.g. Home, Work)').fill('Work');
  await page.getByLabel('Street address').fill('18000 Colima Rd');
  await page.getByLabel('ZIP code').fill('91748');
  await page.getByRole('button', { name: 'Save address' }).click();
  await expect(page.getByText('Address saved.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Deliver to: Work' })).toBeVisible();

  await page.goto('/');
  await expect(page.locator('.restaurant-card').first()).toContainText("Xi'an Spice Road"); // the only kitchen in range comes first
  await page.goto('/r/lucky-star-dumplings');
  await expect(page.getByText('your delivery address is outside that')).toBeVisible();
  await expect(page.getByRole('button', { name: /^Add .* to cart$/ })).toHaveCount(0);
});
