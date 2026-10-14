#!/usr/bin/env bash
# Create a test user in LocalStack Cognito
#
# Usage:
#   ./scripts/create-local-user.sh <email> <password> <group>
#
# Examples:
#   ./scripts/create-local-user.sh customer@test.pl Test1234! CUSTOMER
#   ./scripts/create-local-user.sh operator@test.pl Test1234! OPERATOR
#
# Requires: terraform output available in infrastructure/environments/local/

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_LOCAL="$REPO_ROOT/infrastructure/environments/local"

EMAIL="${1:-customer@test.pl}"
PASSWORD="${2:-Test1234!}"
GROUP="${3:-CUSTOMER}"

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_REGION=eu-central-1

POOL_ID=$(cd "$TF_LOCAL" && terraform output -raw cognito_user_pool_id)

echo "Creating user: $EMAIL in pool $POOL_ID (group: $GROUP)"

aws cognito-idp admin-create-user \
  --user-pool-id "$POOL_ID" \
  --username "$EMAIL" \
  --user-attributes Name=email,Value="$EMAIL" Name=email_verified,Value=true \
  --temporary-password "$PASSWORD" \
  --message-action SUPPRESS \
  --endpoint-url http://localhost:4566 \
  --region eu-central-1 \
  --output json | jq -r '.User.UserStatus'

aws cognito-idp admin-set-user-password \
  --user-pool-id "$POOL_ID" \
  --username "$EMAIL" \
  --password "$PASSWORD" \
  --permanent \
  --endpoint-url http://localhost:4566 \
  --region eu-central-1

aws cognito-idp admin-add-user-to-group \
  --user-pool-id "$POOL_ID" \
  --username "$EMAIL" \
  --group-name "$GROUP" \
  --endpoint-url http://localhost:4566 \
  --region eu-central-1

echo "User $EMAIL created and added to group $GROUP"
echo ""
echo "Get a token with:"
echo "  ./scripts/get-local-token.sh $EMAIL $PASSWORD"
