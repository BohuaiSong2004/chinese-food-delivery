import { expect, test } from '@playwright/test';
import { fileURLToPath } from 'node:url';
import { asRole, signUpAs } from './helpers';

const photo = fileURLToPath(new URL('./fixtures/dish.jpg', import.meta.url));

test('a restaurant owner applies, builds a menu, is approved and goes live', async ({ page, browser }) => {
  const name = `E2E Noodle Bar ${Date.now()}`;
  await signUpAs(page, 'List my restaurant', 'Owner Liu');
  await expect(page).toHaveURL(/\/partner/); // no restaurant yet: straight to the application
  await page.getByLabel('Restaurant name (English)').fill(name);
  await page.getByLabel('Restaurant name (Chinese)').fill('刀削面馆');
  await page.getByLabel('Neighborhood').fill('Alhambra');
  await page.getByLabel('Street address').fill('200 W Main St, Alhambra, CA');
  await page.getByLabel('ZIP code').fill('91801');
  await page.getByLabel('Open 24 hours').check();
  await page.getByRole('button', { name: 'Submit for review' }).click();

  await expect(page).toHaveURL(/\/kitchen/);
  await expect(page.getByText('Your application is under review')).toBeVisible();
  await page.getByLabel('Section name (English)').fill('Noodles');
  await page.getByLabel('Section name (Chinese)').fill('面');
  await page.getByRole('button', { name: 'Add section' }).click();
  await page.getByRole('button', { name: 'Add a dish to Noodles' }).click();
  await page.getByLabel('Dish name (English)').fill('Shanxi Knife-Cut Noodles');
  await page.getByLabel('Dish name (Chinese)').fill('山西刀削面');
  await page.getByLabel('Price (USD)').fill('13.50');
  await page.getByRole('button', { name: 'Add dish' }).click();
  const row = page.locator('.dish-row', { hasText: 'Shanxi Knife-Cut Noodles' });
  await expect(row).toContainText('$13.50');
  await row.getByRole('button', { name: 'Edit Shanxi Knife-Cut Noodles' }).click();
  await page.getByLabel('Photo').setInputFiles(photo);
  await expect(page.getByText('Photo saved.')).toBeVisible();
  await expect(page.locator('.dish-row', { hasText: 'Shanxi Knife-Cut Noodles' }).locator('img')).toBeVisible();

  const admin = await asRole(browser, 'admin@example.com');
  await admin.goto('/admin');
  await admin.getByRole('button', { name: `Approve ${name}` }).click();
  await expect(admin.getByRole('button', { name: `Approve ${name}` })).toHaveCount(0);

  await page.reload();
  await page.getByRole('link', { name: 'View storefront' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText(name);
  const dish = page.locator('.dish', { hasText: 'Shanxi Knife-Cut Noodles' });
  await expect(dish.locator('img')).toHaveAttribute('srcset', /-320\.jpg 320w/);
});

test('a new rider waits for approval, then sees the job board', async ({ page, browser }) => {
  await signUpAs(page, 'Deliver orders', `Rider ${Date.now()}`);
  await expect(page).toHaveURL(/\/rider/);
  await expect(page.getByText('An admin reviews new rider accounts')).toBeVisible();

  const admin = await asRole(browser, 'admin@example.com');
  await admin.goto('/admin');
  const card = admin.locator('[data-rider]').last();
  await card.getByRole('button', { name: /^Approve / }).click();
  await expect(admin.locator('[data-rider]')).toHaveCount(0);

  await page.getByRole('button', { name: 'Check again' }).click();
  await expect(page.getByRole('heading', { name: 'Open jobs' })).toBeVisible();
});
