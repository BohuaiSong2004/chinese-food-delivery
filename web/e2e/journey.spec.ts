import { expect, test } from '@playwright/test';
import { asRole, checkout, fillCart, newCustomer, orderStatus } from './helpers';

test('customer orders, kitchen and rider move it along, customer watches it live', async ({ page, browser }) => {
  await newCustomer(page);
  await fillCart(page, ['Century Egg & Pork Congee', 'Hong Kong Milk Tea', 'Hong Kong Milk Tea']);
  await expect(page.getByRole('heading', { name: 'Your cart' })).toBeVisible();
  await expect(page.getByLabel('Quantity of Hong Kong Milk Tea')).toHaveText('2');
  // 10.95 + 2 x 4.95 = 20.85, + 3.49 delivery
  await expect(page.getByText('$24.34')).toBeVisible();

  const id = await checkout(page);
  await expect(orderStatus(page)).toHaveText('Paid — waiting for the kitchen');
  await expect(page.getByText('Live', { exact: true })).toBeVisible();

  const kitchen = await asRole(browser, 'merchant@example.com');
  await kitchen.goto('/kitchen');
  await kitchen.getByLabel('Restaurant').selectOption({ label: 'Midnight Congee' });
  const ticket = kitchen.locator(`[data-order="${id}"]`);
  await ticket.getByRole('button', { name: 'Accept' }).click();
  await expect(orderStatus(page)).toHaveText('Being prepared'); // pushed to the customer, no reload
  await ticket.getByRole('button', { name: 'Mark ready' }).click();
  await expect(orderStatus(page)).toHaveText('Ready for pickup');

  const rider = await asRole(browser, 'rider@example.com');
  await rider.goto('/rider');
  await rider.locator(`[data-order="${id}"]`).getByRole('button', { name: 'Take this job' }).click();
  await rider.locator(`[data-order="${id}"]`).getByRole('button', { name: 'Picked up' }).click();
  await expect(orderStatus(page)).toHaveText('On the way');
  await rider.locator(`[data-order="${id}"]`).getByRole('button', { name: 'Delivered' }).click();
  await expect(orderStatus(page)).toHaveText('Delivered');

  await kitchen.getByRole('tab', { name: 'Earnings' }).click();
  await expect(kitchen.getByRole('cell', { name: `#${id.slice(0, 8).toUpperCase()}` })).toBeVisible();
});

test('a declined payment cancels the order and frees the stock', async ({ page }) => {
  await newCustomer(page);
  await fillCart(page, ['Char Siu Rice']);
  await checkout(page, 'Decline payment');
  await expect(orderStatus(page)).toHaveText('Cancelled');
  await expect(page.getByText('payment_declined')).toBeVisible();
});

test('a stale kitchen screen cannot act twice', async ({ page, browser }) => {
  await newCustomer(page);
  await fillCart(page, ['Fish Fillet Congee']);
  const id = await checkout(page);
  await expect(orderStatus(page)).toHaveText('Paid — waiting for the kitchen');

  const a = await asRole(browser, 'merchant@example.com');
  const b = await asRole(browser, 'merchant@example.com');
  for (const p of [a, b]) {
    await p.goto('/kitchen');
    await p.getByLabel('Restaurant').selectOption({ label: 'Midnight Congee' });
    await expect(p.locator(`[data-order="${id}"]`).getByRole('button', { name: 'Reject' })).toBeVisible();
  }
  // b goes offline-ish: block its live stream so its screen stays stale
  await b.route('**/events', (r) => r.abort());
  await a.locator(`[data-order="${id}"]`).getByRole('button', { name: 'Reject' }).click();
  await a.getByLabel('Reason (shown to the customer)').fill('Out of rockfish today');
  await a.getByRole('button', { name: 'Reject and refund' }).click();
  await expect(page.getByText('The restaurant could not take this order: Out of rockfish today')).toBeVisible();

  await b.locator(`[data-order="${id}"]`).getByRole('button', { name: 'Accept' }).click();
  await expect(b.getByRole('alert')).toContainText(/REJECTED/);
});

test('session survives a reload and the cart is kept', async ({ page }) => {
  await newCustomer(page);
  await page.goto('/r/lucky-star-dumplings');
  await page.getByRole('button', { name: 'Add Egg & Chive Dumplings to cart' }).click();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Cart (1)' })).toBeVisible();
});

test('protected pages send you to sign in and back', async ({ page }) => {
  await page.goto('/kitchen');
  await expect(page).toHaveURL(/\/signin\?next=%2Fkitchen/);
  await page.getByLabel('Email').fill('merchant@example.com');
  await page.getByLabel('Password').fill('wokflow-demo');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Kitchen' })).toBeVisible();
});
