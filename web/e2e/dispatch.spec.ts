import { expect, test } from '@playwright/test';
import { asRole, checkout, fillCart, newCustomer, orderStatus, PASSWORD, signUpAs } from './helpers';

test('the nearest online rider gets the offer, accepts it, and the customer watches them come', async ({ page, browser, request }) => {
  // a fresh, approved rider (so no leftover deliveries from earlier runs make them busy)
  const riderPage = await (await browser.newContext()).newPage();
  const email = await signUpAs(riderPage, 'Deliver orders', 'Dispatch Tester');
  const admin = await (await request.post('/api/auth/signin', { data: { email: 'admin@example.com', password: PASSWORD } })).json();
  const queue = await (await request.get('/api/admin/applications', { headers: { Authorization: `Bearer ${admin.accessToken}` } })).json();
  const me = queue.riders.find((r: { email: string }) => r.email === email);
  await request.post(`/api/admin/riders/${me.id}/approve`, { headers: { Authorization: `Bearer ${admin.accessToken}` }, data: {} });
  await riderPage.getByRole('button', { name: 'Check again' }).click();
  await riderPage.getByRole('button', { name: 'Go online' }).click(); // demo location, near the Alhambra kitchens
  await expect(riderPage.getByRole('heading', { name: 'You are online' })).toBeVisible();

  await newCustomer(page);
  await fillCart(page, ['Plain Congee with Fried Dough', 'Hong Kong Milk Tea']);
  const id = await checkout(page);
  await expect(orderStatus(page)).toHaveText('Paid — waiting for the kitchen');
  const kitchen = await asRole(browser, 'merchant@example.com');
  await kitchen.goto('/kitchen');
  await kitchen.getByLabel('Restaurant').selectOption({ label: 'Midnight Congee' });
  await kitchen.locator(`[data-order="${id}"]`).getByRole('button', { name: 'Accept' }).click();

  const offer = riderPage.getByRole('alertdialog', { name: 'New delivery offer' });
  await expect(offer).toBeVisible({ timeout: 20_000 });
  await offer.getByRole('button', { name: 'Accept' }).click();
  await expect(riderPage.locator(`[data-order="${id}"]`)).toBeVisible();

  // the simulator drives towards the kitchen; the customer sees the rider and the distance shrink
  const tracker = page.getByRole('heading', { name: 'Dispatch Tester is heading to the kitchen' });
  await expect(tracker).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('.rider-progress')).toContainText(/away · about \d+ min/);
  await riderPage.getByRole('button', { name: 'Go offline' }).click();
});
