import { expect, test } from '@playwright/test';
import { newCustomer } from './helpers';

const MAILPIT = process.env.MAILPIT_URL ?? 'http://localhost:8025';

/** Polls the local Mailpit inbox for the newest reset link sent to an address. */
async function resetLink(email: string): Promise<string> {
  let link: string | undefined;
  await expect.poll(async () => {
    const res = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`);
    const found = await res.json() as { messages: { ID: string }[] };
    const newest = found.messages[0];
    if (!newest) return false;
    const msg = await (await fetch(`${MAILPIT}/api/v1/message/${newest.ID}`)).json() as { Text: string };
    link = /https?:\/\/\S+\/reset#token=[A-Za-z0-9_-]+/.exec(msg.Text)?.[0];
    return Boolean(link);
  }, { timeout: 15_000 }).toBe(true);
  return link!;
}

test('a forgotten password is reset from the emailed link, which works once', async ({ page }) => {
  const email = await newCustomer(page);
  await page.getByRole('button', { name: 'Sign out' }).click();

  await page.goto('/signin');
  await page.getByRole('link', { name: 'Forgot your password?' }).click();
  await page.getByLabel('Email').fill(email);
  await page.getByRole('button', { name: 'Send reset link' }).click();
  await expect(page.getByRole('status')).toContainText('reset link is on its way');

  const link = new URL(await resetLink(email));
  await page.goto(link.pathname + link.hash);
  await expect(page).toHaveURL(/\/reset$/); // the token is dropped from the address bar and history
  await page.getByLabel('New password').fill('fresh-password-456');
  await page.getByLabel('Type it again').fill('fresh-password-456');
  await page.getByRole('button', { name: 'Save password' }).click();
  await expect(page.getByRole('status')).toContainText('Password changed');

  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('fresh-password-456');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();

  await page.goto(link.pathname + link.hash);
  await page.getByLabel('New password').fill('another-password-789');
  await page.getByLabel('Type it again').fill('another-password-789');
  await page.getByRole('button', { name: 'Save password' }).click();
  await expect(page.getByRole('alert')).toContainText('expired or was already used');
});

test('a signed-in customer changes their password and stays signed in', async ({ page }) => {
  await newCustomer(page);
  await page.getByRole('link', { name: 'Change password' }).click();
  await page.getByLabel('Current password').fill('not-my-password');
  await page.getByLabel('New password').fill('changed-password-1');
  await page.getByRole('button', { name: 'Save password' }).click();
  await expect(page.getByRole('alert')).toContainText('not correct');

  await page.getByLabel('Current password').fill('e2e-password-123');
  await page.getByRole('button', { name: 'Save password' }).click();
  await expect(page.getByRole('status')).toContainText('Password changed');
  await page.reload();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
});
