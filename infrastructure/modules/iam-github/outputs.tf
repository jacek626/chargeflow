output "deploy_role_arn" {
  description = "ARN of the IAM role assumed by GitHub Actions (set as AWS_DEPLOY_ROLE_ARN in GitHub)"
  value       = aws_iam_role.deploy.arn
}
