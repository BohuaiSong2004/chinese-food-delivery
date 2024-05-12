import { expect, test } from '@playwright/test';

test('search understands typos, Chinese and pinyin initials', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Search dishes').fill('mpdf');
  await page.getByRole('button', { name: 'Search' }).click();
  await expect(page).toHaveURL(/\/search\?q=mpdf/);
  await expect(page.getByRole('link', { name: /Mapo Tofu/ })).toBeVisible();

  await page.getByLabel('Dish, restaurant, Chinese or pinyin').fill('dumplng');
  await page.getByRole('button', { name: 'Search' }).click();
  await expect(page.getByRole('link', { name: /Dumplings/ }).first()).toBeVisible();

  await page.getByLabel('Dish, restaurant, Chinese or pinyin').fill('牛肉面');
  await page.getByRole('button', { name: 'Search' }).click();
  await expect(page.getByRole('link', { name: /Beef Noodle Soup/ }).first()).toBeVisible();
});

test('tag filters narrow results and survive in the URL', async ({ page }) => {
  await page.goto('/search?q=noodles');
  // the checkbox is driven by the URL, which updates on the next render
  await page.getByLabel(/^Vegan/).click();
  await expect(page).toHaveURL(/tag=vegan/);
  await expect(page.getByLabel(/^Vegan/)).toBeChecked();
  await expect(page.locator('.dish-hit').first()).toBeVisible();
  for (const tags of await page.locator('.dish-hit .tags').allInnerTexts()) {
    expect(tags).toContain('Vegan');
  }
  await page.getByLabel('Maximum spice').selectOption('0');
  await expect(page).toHaveURL(/maxSpice=0/);
  await expect(page.locator('.spice')).toHaveCount(0);
});

test('Chinese interface @mobile', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Switch to Chinese' }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('地道中餐，锅气直达家门');
  await page.getByRole('link', { name: '川菜' }).click();
  await expect(page.getByText(/共 \d+ 道菜/)).toBeVisible();
  await expect(page.getByRole('link', { name: /麻婆豆腐/ })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('搜索');
});
