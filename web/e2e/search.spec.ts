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
  // results for the new filter, not the previous ones still on screen while it loads
  await expect(page.locator('section[aria-busy="false"] .dish-hit').first()).toBeVisible();
  for (const tags of await page.locator('.dish-hit .tags').allInnerTexts()) {
    expect(tags).toContain('Vegan');
  }
  await page.getByLabel('Maximum spice').selectOption('0');
  await expect(page).toHaveURL(/maxSpice=0/);
  await expect(page.locator('section[aria-busy="false"]')).toBeVisible();
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

test('suggestions while typing: keyboard, pinyin initials and restaurants', async ({ page }) => {
  await page.goto('/');
  const box = page.getByRole('combobox', { name: 'Search dishes' });
  await box.fill('mpdf');
  const list = page.getByRole('listbox', { name: 'Suggestions' });
  await expect(list.getByRole('option', { name: /Mapo Tofu/ })).toBeVisible();
  await box.press('ArrowDown');
  await expect(box).toHaveAttribute('aria-activedescendant', /.+/);
  await expect(list.getByRole('option', { selected: true })).toContainText('Mapo Tofu');
  await box.press('Enter');
  await expect(page).toHaveURL(/\/r\/chengdu-night-market#dish-\d+/);

  await page.goto('/');
  await box.fill('cdys'); // initials of 成都夜市
  await expect(list.getByRole('option', { name: /Chengdu Night Market/ })).toBeVisible();
  await box.press('Escape');
  await expect(list).toBeHidden();
  await expect(box).toHaveAttribute('aria-expanded', 'false');
});

test('recent searches, synonyms and highlighted matches', async ({ page }) => {
  await page.goto('/');
  const box = page.getByRole('combobox', { name: 'Search dishes' });
  await box.fill('boba');
  await box.press('Enter');
  await expect(page).toHaveURL(/\/search\?q=boba/);
  const hit = page.locator('.dish-hit').first();
  await expect(hit).toContainText('Bubble Milk Tea'); // another name for 珍珠奶茶
  await expect(hit.locator('mark').first()).toBeVisible();

  await page.goto('/');
  await page.getByRole('combobox', { name: 'Search dishes' }).focus();
  const recent = page.getByRole('group', { name: 'Recent searches' });
  await expect(recent.getByRole('option', { name: 'boba' })).toBeVisible();
  await recent.getByRole('option', { name: 'boba' }).click();
  await expect(page).toHaveURL(/\/search\?q=boba/);
});

test('no exact match, filters that leave nothing, and sorting', async ({ page }) => {
  await page.goto('/search?q=spicy%20fish');
  await expect(page.getByText(/No dish matches every word of “spicy fish”/)).toBeVisible();
  await expect(page.getByRole('link', { name: /Fish in Chili Oil/ })).toBeVisible();

  await page.goto('/search?q=noodles&tag=dessert');
  await page.getByRole('button', { name: /Show \d+ dishes without the filters/ }).click();
  await expect(page).not.toHaveURL(/tag=dessert/);
  await expect(page.locator('section[aria-busy="false"] .dish-hit').first()).toBeVisible();

  await page.getByLabel('Sort by').selectOption('price');
  await expect(page).toHaveURL(/sort=price/);
  await expect(page.locator('section[aria-busy="false"] .dish-hit').first()).toBeVisible();
  const prices = (await page.locator('.dish-hit .muted').allInnerTexts())
    .map((s) => Number(/\$(\d+\.\d\d)/.exec(s)?.[1]));
  expect(prices.length).toBeGreaterThan(3);
  expect([...prices].sort((a, b) => a - b)).toEqual(prices);
});
