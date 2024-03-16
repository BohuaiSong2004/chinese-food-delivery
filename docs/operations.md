# Operations

Runbooks for the things that change while the system is running. Everything here has a test
that exercises it; the test is named after each section.

## Rotating the JWT signing key

Access tokens are ES256 JWTs that live 15 minutes and name their key in the `kid` header.
Refresh tokens are opaque database rows, so a key rotation never signs anyone out.

1. `scripts/rotate-jwt-key.sh secrets/jwt.pem secrets/jwt-retired.pem`
   appends the current public key to the retired file and writes a fresh key in its place
   (the old file is kept as `jwt.pem.old`).
2. Deploy with `JWT_PREVIOUS_PUBLIC_KEYS_FILE=/keys/jwt-retired.pem` (or the PEM text in
   `JWT_PREVIOUS_PUBLIC_KEYS`, e.g. from a secrets manager). Every replica now signs with
   the new key and still accepts the old one; `/.well-known/jwks.json` lists both, the
   signing key first.
3. Once every replica runs the new key, wait one access-token lifetime, then empty the
   retired file and deploy again. Tokens signed with the old key now fail with 401 and the
   web client silently refreshes.

A leaked key is the same procedure with step 3 done straight away: the old key's tokens stop
working at once, and the clients refresh.

Test: `JwtKeysTest.tokensSignedBeforeARotationVerifyUntilTheOldKeyIsDropped`.

## Rotating the payment webhook secret

`MOCKPAY_WEBHOOK_SECRET` may list several secrets, newest first: `whsec_new,whsec_old`.

1. Deploy the API with `whsec_new,whsec_old`; it accepts webhooks signed with either.
2. Switch the payment provider to sign with `whsec_new`. Providers that sign with both
   during their own rotation window send two `v1=` values in one header; either matching is
   enough.
3. Deploy the API with `whsec_new` alone.

Test: `WebhookVerifierTest.acceptsTheNewAndTheOldSecretWhileTheSettingListsBoth`.

## Password-reset mail

The API sends mail over SMTP (`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, plus
`SPRING_MAIL_USERNAME`/`SPRING_MAIL_PASSWORD` for a real relay such as SES SMTP).
Locally, compose runs Mailpit: open <http://localhost:8025> to read what was sent. Without a
mail host the API logs a masked address and never the link.

Test: `PasswordIT`.
