import { expect, test } from '@playwright/test';

// The site opened at another address than PUBLIC_ORIGIN (127.0.0.1, a LAN IP) used to sign in and then
// lose the session on every reload: the refresh call was refused as cross-origin.
test('a session survives a reload when the site is opened as 127.0.0.1', async ({ page, baseURL }) => {
  const url = new URL(baseURL ?? 'http://localhost:8000');
  test.skip(url.hostname !== 'localhost', 'needs a localhost base URL to swap for 127.0.0.1');
  url.hostname = '127.0.0.1';
  const served = await page.request.get(url.toString()).then((r) => r.ok()).catch(() => false);
  test.skip(!served, '127.0.0.1 is not served here (a dev server may listen on ::1 only)');
  await page.goto(new URL('/signin', url).toString());
  await page.getByLabel('Email').fill('customer@example.com');
  await page.getByLabel('Password', { exact: true }).fill('wokflow-demo');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
});
