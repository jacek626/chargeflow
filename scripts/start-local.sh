#!/usr/bin/env bash
# ChargeFlow — local development startup script
#
# Usage:
#   ./scripts/start-local.sh            # start infra + print env vars
#   ./scripts/start-local.sh --services # also start both backend services
#   ./scripts/start-local.sh --down     # stop everything
#
# Prerequisites:
#   docker, terraform, java 25 (JAVA_HOME), node 20+

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_LOCAL="$REPO_ROOT/infrastructure/environments/local"

# ── helpers ───────────────────────────────────────────────────────────────────

info()    { printf "\033[0;34m[INFO]\033[0m  %s\n" "$*"; }
success() { printf "\033[0;32m[OK]\033[0m    %s\n" "$*"; }
warn()    { printf "\033[0;33m[WARN]\033[0m  %s\n" "$*"; }
die()     { printf "\033[0;31m[ERROR]\033[0m %s\n" "$*" >&2; exit 1; }

require() { command -v "$1" &>/dev/null || die "$1 not found — install it first"; }

# ── --down ────────────────────────────────────────────────────────────────────

if [[ "${1:-}" == "--down" ]]; then
  info "Stopping Docker Compose services..."
  cd "$REPO_ROOT" && docker compose down
  success "Done."
  exit 0
fi

# ── prerequisite check ────────────────────────────────────────────────────────

require docker
require terraform
require java
require node

info "Checking Java version..."
JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '(?<=version ")[0-9]+')
[[ "$JAVA_VER" -ge 25 ]] || die "Java 25+ required (found Java $JAVA_VER). Set JAVA_HOME correctly."
success "Java $JAVA_VER"

# ── 1. Start Docker Compose ───────────────────────────────────────────────────

info "Starting PostgreSQL + LocalStack..."
cd "$REPO_ROOT"
[[ -f .env ]] || { warn ".env not found — copying from .env.example"; cp .env.example .env; }
docker compose up -d

info "Waiting for LocalStack to be healthy..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:4566/_localstack/health | grep -q '"running"'; then
    break
  fi
  sleep 2
  [[ $i -eq 30 ]] && die "LocalStack did not become healthy after 60 s"
done
success "LocalStack is up"

info "Waiting for PostgreSQL..."
for i in $(seq 1 20); do
  if docker exec chargeflow-postgres pg_isready -U chargeflow -d chargeflow &>/dev/null; then
    break
  fi
  sleep 2
  [[ $i -eq 20 ]] && die "PostgreSQL did not become ready"
done
success "PostgreSQL is up"

# ── 2. Terraform apply (local → LocalStack) ───────────────────────────────────

info "Provisioning LocalStack resources with Terraform..."
cd "$TF_LOCAL"

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_REGION=eu-central-1

terraform init -upgrade -input=false > /dev/null
terraform apply -auto-approve -input=false
success "Terraform apply complete"

# ── 3. Collect outputs ────────────────────────────────────────────────────────

SNS_TOPIC_ARN=$(terraform output -raw sns_topic_arn)
SQS_QUEUE_URL=$(terraform output -raw sqs_queue_url)
DYNAMODB_TABLE=$(terraform output -raw dynamodb_notifications_table_name)

cd "$REPO_ROOT"

# ── 4. Print env vars ─────────────────────────────────────────────────────────

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  ChargeFlow local environment ready"
echo "═══════════════════════════════════════════════════════════════════════"
echo ""
echo "  PostgreSQL:  localhost:5432  (db: chargeflow, user: chargeflow)"
echo "  LocalStack:  http://localhost:4566"
echo ""
echo "  Auth: handled by Spring profile 'local' (self-signed JWT, no Cognito needed)"
echo "  Dev token endpoint: POST http://localhost:8080/dev/token"
echo "  Dev JWKS endpoint:  GET  http://localhost:8080/dev/jwks.json"
echo ""
echo "─── charging-session-service env vars (port 8080) ─────────────────────"
echo ""
echo "  export SPRING_PROFILES_ACTIVE=local"
echo "  export DB_URL=jdbc:postgresql://localhost:5432/chargeflow"
echo "  export DB_USERNAME=chargeflow"
echo "  export DB_PASSWORD=chargeflow_local"
echo "  export AWS_REGION=eu-central-1"
echo "  export AWS_ACCESS_KEY_ID=test"
echo "  export AWS_SECRET_ACCESS_KEY=test"
echo "  export AWS_ENDPOINT_URL=http://localhost:4566"
echo "  export SNS_ENABLED=true"
echo "  export SNS_TOPIC_ARN=$SNS_TOPIC_ARN"
echo ""
echo "─── notification-service env vars (port 8081) ──────────────────────────"
echo ""
echo "  export AWS_REGION=eu-central-1"
echo "  export AWS_ACCESS_KEY_ID=test"
echo "  export AWS_SECRET_ACCESS_KEY=test"
echo "  export AWS_ENDPOINT_URL=http://localhost:4566"
echo "  export SQS_ENABLED=true"
echo "  export SQS_QUEUE_URL=$SQS_QUEUE_URL"
echo "  export DYNAMODB_NOTIFICATIONS_TABLE=$DYNAMODB_TABLE"
echo "  export SES_ENABLED=false"
echo ""
echo "─── frontend (port 4201) ───────────────────────────────────────────────"
echo ""
echo "  environment.ts: skipAuth=true, apiBaseUrl=http://localhost:8080"
echo "  (already set — no changes needed)"
echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo ""
echo "  Quick tests:"
echo "  curl http://localhost:8080/actuator/health"
echo "  curl http://localhost:8080/api/stations"
echo ""
echo "  Get a dev token (CUSTOMER):"
echo "  curl -X POST http://localhost:8080/dev/token -d 'role=CUSTOMER'"
echo ""
echo "  Get a dev token (OPERATOR):"
echo "  curl -X POST http://localhost:8080/dev/token -d 'role=OPERATOR'"
echo ""

# ── 5. Optionally start services ─────────────────────────────────────────────

if [[ "${1:-}" == "--services" ]]; then
  info "Starting charging-session-service in background..."
  (
    cd "$REPO_ROOT/charging-session-service"
    export SPRING_PROFILES_ACTIVE=local
    export DB_URL=jdbc:postgresql://localhost:5432/chargeflow
    export DB_USERNAME=chargeflow
    export DB_PASSWORD=chargeflow_local
    export AWS_REGION=eu-central-1
    export AWS_ACCESS_KEY_ID=test
    export AWS_SECRET_ACCESS_KEY=test
    export AWS_ENDPOINT_URL=http://localhost:4566
    export SNS_ENABLED=true
    export SNS_TOPIC_ARN="$SNS_TOPIC_ARN"
    ./mvnw spring-boot:run -q &
    echo $! > /tmp/chargeflow-session-service.pid
  )

  info "Starting notification-service in background..."
  (
    cd "$REPO_ROOT/notification-service"
    export AWS_REGION=eu-central-1
    export AWS_ACCESS_KEY_ID=test
    export AWS_SECRET_ACCESS_KEY=test
    export AWS_ENDPOINT_URL=http://localhost:4566
    export SQS_ENABLED=true
    export SQS_QUEUE_URL="$SQS_QUEUE_URL"
    export DYNAMODB_NOTIFICATIONS_TABLE="$DYNAMODB_TABLE"
    export SES_ENABLED=false
    ./mvnw spring-boot:run -q &
    echo $! > /tmp/chargeflow-notification-service.pid
  )

  echo ""
  info "Services starting... wait ~30s then check:"
  echo "  curl http://localhost:8080/actuator/health"
  echo "  curl http://localhost:8081/actuator/health"
  echo ""
  info "To stop services:"
  echo "  kill \$(cat /tmp/chargeflow-session-service.pid)"
  echo "  kill \$(cat /tmp/chargeflow-notification-service.pid)"
fi
