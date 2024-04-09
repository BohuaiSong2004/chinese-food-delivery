# Values the application reads at start-up. The JWT signing key and the payment provider secrets are
# created empty and set out of band (see infra/README.md), so they never pass through Terraform state.

resource "aws_secretsmanager_secret" "db_password" {
  #checkov:skip=CKV2_AWS_57:Rotation needs the app to reload pool credentials without a restart; planned (see infra/README.md)
  name       = "${local.name}/db-password"
  kms_key_id = aws_kms_key.main.arn
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db.result
}

resource "aws_secretsmanager_secret" "redis_token" {
  #checkov:skip=CKV2_AWS_57:ElastiCache token rotation needs a two-token rollover in the app; planned (see infra/README.md)
  name       = "${local.name}/redis-auth-token"
  kms_key_id = aws_kms_key.main.arn
}

resource "aws_secretsmanager_secret_version" "redis_token" {
  secret_id     = aws_secretsmanager_secret.redis_token.id
  secret_string = random_password.redis.result
}

resource "aws_secretsmanager_secret" "es_password" {
  #checkov:skip=CKV2_AWS_57:Owned by Elastic Cloud; rotated there and re-synced by terraform apply
  name       = "${local.name}/elasticsearch-password"
  kms_key_id = aws_kms_key.main.arn
}

resource "aws_secretsmanager_secret_version" "es_password" {
  secret_id     = aws_secretsmanager_secret.es_password.id
  secret_string = ec_deployment.search.elasticsearch_password
}

# Set by an operator: `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256` (see scripts/gen-jwt-key.sh).
resource "aws_secretsmanager_secret" "jwt_key" {
  #checkov:skip=CKV2_AWS_57:Rotation needs a second verification key (kid) during rollover; planned (see infra/README.md)
  name       = "${local.name}/jwt-signing-key"
  kms_key_id = aws_kms_key.main.arn
}

resource "aws_secretsmanager_secret" "payment_secret_key" {
  #checkov:skip=CKV2_AWS_57:Issued and rotated by the payment provider
  name       = "${local.name}/payment-secret-key"
  kms_key_id = aws_kms_key.main.arn
}

resource "aws_secretsmanager_secret" "payment_webhook_secret" {
  #checkov:skip=CKV2_AWS_57:Issued and rotated by the payment provider; the verifier accepts several v1 signatures for rollover
  name       = "${local.name}/payment-webhook-secret"
  kms_key_id = aws_kms_key.main.arn
}
