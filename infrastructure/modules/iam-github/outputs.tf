# ARN roli deploy — wpisywany w GitHubie jako AWS_DEPLOY_ROLE_ARN (role-to-assume w deploy.yml).
output "deploy_role_arn" {
  description = "ARN of the IAM role assumed by GitHub Actions (set as AWS_DEPLOY_ROLE_ARN in GitHub)"
  value       = aws_iam_role.deploy.arn
}
