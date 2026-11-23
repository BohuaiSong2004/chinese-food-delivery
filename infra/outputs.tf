output "url" {
  description = "Public URL."
  value       = "https://${var.domain_name}"
}

output "web_bucket" {
  description = "Upload the built web app (web/dist) here."
  value       = aws_s3_bucket.web.id
}

output "cloudfront_distribution_id" {
  description = "Invalidate /index.html here after uploading a new build."
  value       = aws_cloudfront_distribution.main.id
}

output "api_repository_url" {
  description = "Push API images here."
  value       = aws_ecr_repository.api.repository_url
}

output "payment_webhook_url" {
  description = "Register this as the payment provider's webhook endpoint."
  value       = "https://${var.domain_name}/api/payments/webhook"
}
