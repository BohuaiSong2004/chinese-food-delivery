# Infrastructure (AWS + Elastic Cloud)

Terraform for a production deployment. It is **validated, linted (tflint, AWS ruleset) and scanned
(Checkov) in CI, but has not been applied** — applying it needs an AWS account, a Route 53 zone and an
Elastic Cloud API key, and it costs money.

```
            ┌──────────── CloudFront (WAF: AWS managed rules + per-IP rate limit, US only) ────────────┐
 browser ──►│  /*      → S3 (web/dist, origin access control)                                           │
            │  /api/*  → ALB (HTTPS, accepts CloudFront origin IPs + secret header only)                │
            └──────────────────────────────────────┬──────────────────────────────────────────────────┘
                                                   ▼
                              ECS Fargate: API (arm64, 2–8 tasks, CPU target tracking)
                               │                 │                    │
                     RDS Postgres 18     ElastiCache Redis 7     Elastic Cloud 9.5
                     Multi-AZ, KMS,      TLS + auth token,       traffic filter: NAT
                     force SSL, PI       Multi-AZ                gateway IPs only
```

| File | Contents |
|---|---|
| `network.tf` | VPC, public/private subnets in two AZs, one NAT gateway per AZ, flow logs, security groups, default SG locked |
| `data.tf` | KMS key, RDS Postgres, ElastiCache Redis, Elastic Cloud deployment and traffic filter |
| `secrets.tf` | Secrets Manager entries (DB, Redis, Elasticsearch, JWT key, payment provider) |
| `ecs.tf` | ECS cluster, task definition (read-only root FS), service with circuit-breaker rollback, autoscaling, 5xx alarm, ECR |
| `edge.tf` | CloudFront, S3 (static site + logs), ALB, ACM certificates, Route 53 records, WAF |

## Mapping from the local stack

| Local (`docker compose`) | Production |
|---|---|
| nginx serving `web/dist` and proxying `/api` | CloudFront + S3; `/api/*` behaviour to the ALB |
| two `api` replicas | ECS service, 2–8 tasks |
| Postgres container + volume | RDS, Multi-AZ, encrypted, 14-day backups |
| Redis with AOF | ElastiCache (no AOF; hot-menu counters rebuild from the ledger, see `HotMenuService`) |
| Elasticsearch single node | Elastic Cloud (the 9.x Java client does not support Amazon OpenSearch) |
| MockPay | a real provider at `payment_provider_url`; the API talks to it through `PaymentProvider` |
| JWT key generated into a volume | Secrets Manager → `JWT_PRIVATE_KEY` env var |

The SSE stream survives CloudFront and the ALB because the API sends a heartbeat every 20 s (CloudFront's
origin read timeout is 60 s, the ALB idle timeout 120 s) and closes the stream when the access token
expires, so clients reconnect with a fresh token anyway.

## Deploying (outline)

1. `terraform init` with a remote backend, then `cp terraform.tfvars.example terraform.tfvars` and fill it in.
2. `terraform apply` (needs `EC_API_KEY` for Elastic Cloud).
3. Set the secrets created empty:
   `scripts/gen-jwt-key.sh /tmp/jwt.pem && aws secretsmanager put-secret-value --secret-id wokflow-prod/jwt-signing-key --secret-string file:///tmp/jwt.pem`,
   then the payment provider's secret key and webhook secret.
4. Build and push the API image to the `api_repository_url` output with an immutable tag, set `api_image`, apply again.
5. `npm run build` in `web/`, `aws s3 sync web/dist s3://<web_bucket> --delete`, invalidate `/index.html`.
6. Register `payment_webhook_url` with the payment provider.

## Not done / next steps

- Never applied: no real cost, latency or availability numbers come from AWS.
- Credential rotation (DB password, Redis token, JWT key) needs the application to reload credentials or
  accept two keys during a rollover; the Checkov skips in `secrets.tf` say so explicitly.
- The ElastiCache primary endpoint is fine for a single shard; the hot-menu Lua scripts touch several keys,
  so cluster mode would need hash tags on the key names.
