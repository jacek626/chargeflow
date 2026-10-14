# Infrastructure — CLAUDE.md

Infrastructure is managed with **Terraform**.

## Rules

- Do not manually create application infrastructure in AWS
- Prefer reusable modules
- Separate environments
- Never commit credentials
- Never commit Terraform state
- Never commit secrets

## Structure

```
modules/
environments/
  local/
  dev/
  prod/
```

## Planned AWS services for V1

- IAM
- VPC
- Security Groups
- ECS Fargate
- ECR
- ALB
- RDS PostgreSQL
- DynamoDB
- SNS
- SQS
- DLQ
- SES
- Cognito
- S3
- CloudFront
- Secrets Manager
- CloudWatch
- Lambda
- API Gateway
- Route 53
- ACM
- KMS

EC2 will be used only as a learning lab, not as the primary ChargeFlow runtime.

## Required Terraform checks

```
terraform fmt -check
terraform validate
```
