import { expect, type Browser, type Page } from '@playwright/test';

export const PASSWORD = 'wokflow-demo';

export async function signIn(page: Page, email: string, password = PASSWORD) {
  await page.goto('/signin');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

export async function newCustomer(page: Page) {
  const email = `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  await page.goto('/signup');
  await page.getByLabel('Your name').fill('E2E Diner');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('e2e-password-123');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  return email;
}

export async function asRole(browser: Browser, email: string) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await signIn(page, email);
  return page;
}

/** Puts dishes in the cart from Midnight Congee (open 24 h, so tests pass at any hour). */
export async function fillCart(page: Page, dishes: string[]) {
  await page.goto('/r/midnight-congee');
  for (const d of dishes) {
    await page.getByRole('button', { name: `Add ${d} to cart` }).click();
  }
  await page.getByRole('link', { name: /View cart/ }).click();
}

/** Saves a delivery address from the cart (first order of a new customer). */
export async function addAddressInCart(page: Page, street = '88 Garfield Ave', zip = '91801') {
  await page.getByRole('button', { name: 'Add a new address' }).click();
  await page.getByLabel('Street address').fill(street);
  await page.getByLabel('ZIP code').fill(zip);
  await page.getByRole('button', { name: 'Save address' }).click();
  await expect(page.getByRole('radio', { name: new RegExp(street) })).toBeChecked();
}

/** Places the order and pays (or declines) on the sandbox provider's hosted page; returns the order id. */
export async function checkout(page: Page, outcome: 'Pay' | 'Decline payment' = 'Pay') {
  const noAddress = page.getByText('Add a delivery address to place your order.');
  await expect(noAddress.or(page.getByRole('radio', { checked: true }))).toBeVisible(); // addresses have loaded
  if (await noAddress.isVisible()) await addAddressInCart(page);
  await page.getByRole('button', { name: 'Place order and pay' }).click();
  await expect(page).toHaveURL(/\/checkout\/pi_/);
  await expect(page.getByText('Sandbox')).toBeVisible();
  await page.getByRole('button', { name: outcome === 'Pay' ? /^Pay \$/ : 'Decline payment' }).click();
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}\?payment=/);
  return new URL(page.url()).pathname.split('/').pop()!;
}

/** The visible status badge on the order page (the live region also holds a screen-reader copy). */
export const orderStatus = (page: Page) => page.locator('.order-status .badge');

export const uniqueEmail = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;

/** Signs up through the form with an account type (customer, restaurant owner or rider). */
export async function signUpAs(page: Page, type: 'Order food' | 'List my restaurant' | 'Deliver orders', name: string) {
  const email = uniqueEmail(type === 'Order food' ? 'e2e' : type === 'List my restaurant' ? 'owner' : 'rider');
  await page.goto('/signup');
  await page.getByRole('radio', { name: type }).check();
  await page.getByLabel('Your name').fill(name);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('e2e-password-123');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  return email;
}
