# Nazwa bucketa — pipeline deploy wgrywa tu build Angulara (aws s3 sync).
output "s3_bucket_name" {
  description = "S3 bucket name for the Angular SPA"
  value       = aws_s3_bucket.frontend.id
}

# ID dystrybucji — potrzebne do invalidacji cache po deployu (aws cloudfront create-invalidation).
output "cloudfront_distribution_id" {
  description = "CloudFront distribution ID"
  value       = aws_cloudfront_distribution.frontend.id
}

# Domena dystrybucji (xxxx.cloudfront.net) — adres aplikacji, gdy nie ma własnej domeny.
output "cloudfront_domain_name" {
  description = "CloudFront distribution domain name"
  value       = aws_cloudfront_distribution.frontend.domain_name
}
