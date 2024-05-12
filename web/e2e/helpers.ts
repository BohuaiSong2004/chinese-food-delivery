import { expect, type Browser, type Page } from '@playwright/test';

export const PASSWORD = 'wokflow-demo';

export async function signIn(page: Page, email: string, password = PASSWORD) {
  await page.goto('/signin');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

export async function newCustomer(page: Page) {
  const email = `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  await page.goto('/signup');
  await page.getByLabel('Your name').fill('E2E Diner');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('e2e-password-123');
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

/** Places the order and pays (or declines) on the sandbox provider's hosted page; returns the order id. */
export async function checkout(page: Page, outcome: 'Pay' | 'Decline payment' = 'Pay') {
  await page.getByLabel('Delivery address').fill('88 Garfield Ave, Alhambra');
  await page.getByRole('button', { name: 'Place order and pay' }).click();
  await expect(page).toHaveURL(/\/checkout\/pi_/);
  await expect(page.getByText('Sandbox')).toBeVisible();
  await page.getByRole('button', { name: outcome === 'Pay' ? /^Pay \$/ : 'Decline payment' }).click();
  await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}\?payment=/);
  return new URL(page.url()).pathname.split('/').pop()!;
}

/** The visible status badge on the order page (the live region also holds a screen-reader copy). */
export const orderStatus = (page: Page) => page.locator('.order-status .badge');
