# ChargeFlow — Infrastructure

Terraform-managed infrastructure for ChargeFlow supporting three environments:

| Environment | Target |
|---|---|
| `local` | LocalStack running in Docker |
| `dev` | Real AWS account (development) |
| `prod` | Real AWS account (production) |

---

## Directory structure

```
infrastructure/
├── modules/                   # Reusable Terraform modules (not yet implemented)
│   ├── network/               # VPC, subnets, routing
│   ├── ecr/                   # Elastic Container Registry
│   ├── ecs/                   # ECS Fargate cluster and services
│   ├── alb/                   # Application Load Balancer
│   ├── rds/                   # RDS PostgreSQL
│   ├── messaging/             # SNS + SQS + DLQ
│   ├── dynamodb/              # DynamoDB tables
│   ├── cognito/               # Cognito User Pool
│   ├── frontend/              # S3 + CloudFront
│   ├── lambda/                # Lambda functions
│   ├── api-gateway/           # API Gateway
│   ├── monitoring/            # CloudWatch dashboards and alarms
│   ├── kms/                   # KMS keys
│   └── route53/               # Route 53 hosted zones and records
│
└── environments/
    ├── local/                 # LocalStack — dev workstation
    ├── dev/                   # AWS development account
    └── prod/                  # AWS production account
```

Each environment root module contains:
- `versions.tf` — Terraform and provider version constraints
- `providers.tf` — AWS provider configuration and common locals
- `variables.tf` — input variables with validation
- `outputs.tf` — outputs (empty until resources are implemented)
- `terraform.tfvars.example` — example values (committed; actual `.tfvars` are gitignored)

Modules contain only `main.tf`, `variables.tf`, and `outputs.tf` once implemented. They have no environment-specific logic.

---

## Versions

| Tool | Version |
|---|---|
| Terraform | 1.16.2 |
| AWS Provider | 6.64.0 |
| Default region | `eu-central-1` |

Terraform version constraint: `~> 1.16.2` (>= 1.16.2, < 1.17.0)  
AWS Provider constraint: `~> 6.64.0` (>= 6.64.0, < 6.65.0)

---

## Naming convention

All future AWS resources follow: `chargeflow-{environment}-{resource}`

Examples: `chargeflow-dev-vpc`, `chargeflow-prod-session-service`.

The `name_prefix` local value is pre-configured in each environment:

```hcl
locals {
  name_prefix = "${var.project_name}-${var.environment}"
}
```

---

## Common tags

All AWS resources receive default tags via the AWS provider `default_tags` block:

```hcl
common_tags = {
  Project     = "ChargeFlow"
  Environment = var.environment
  ManagedBy   = "Terraform"
}
```

---

## Terraform state

State is local for now (default backend). Remote state on S3 + DynamoDB will be introduced in a dedicated task after the AWS bootstrap strategy is finalized. Do not manually create S3 buckets or DynamoDB tables for state.

Lock files (`.terraform.lock.hcl`) are committed to version control to pin provider checksums across machines.

---

## Local environment — LocalStack

The `local` environment targets LocalStack running via Docker Compose (started in CF-005).

Authentication and the LocalStack endpoint are supplied via environment variables — no credentials are hardcoded in Terraform files.

### Start infrastructure

```bash
# From the repository root
docker compose up -d
```

### Initialize and plan

```bash
cd infrastructure/environments/local

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=eu-central-1
export AWS_ENDPOINT_URL=http://localhost:4566

terraform init
terraform fmt -check
terraform validate
terraform plan -var="environment=local"
```

Expected plan output: `No changes. Your infrastructure matches the configuration.`

---

## Dev and prod environments — real AWS

Authentication uses standard AWS credential mechanisms. Do not hardcode credentials.

In CI/CD (GitHub Actions): OIDC federation will be used — no long-lived access keys.

### Static validation (no AWS access required)

```bash
cd infrastructure/environments/dev   # or prod

terraform init
terraform validate
```

### Plan (requires AWS credentials)

```bash
export AWS_PROFILE=chargeflow-dev   # or set AWS_ACCESS_KEY_ID/SECRET

terraform plan -var="environment=dev"
```

---

## Credentials policy

- No credentials committed in `.tf` files or `.tfvars` files
- `.tfvars` files are gitignored; `.tfvars.example` files are committed
- `terraform.tfstate` and all state files are gitignored
- `.terraform/` directories are gitignored
