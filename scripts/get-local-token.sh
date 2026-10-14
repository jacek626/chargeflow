#!/usr/bin/env bash
# Get an access token from LocalStack Cognito
#
# Usage:
#   ./scripts/get-local-token.sh <email> <password>
#   eval $(./scripts/get-local-token.sh customer@test.pl Test1234!)   # sets TOKEN env var
#
# Requires: aws cli, jq

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_LOCAL="$REPO_ROOT/infrastructure/environments/local"

EMAIL="${1:?Usage: get-local-token.sh <email> <password>}"
PASSWORD="${2:?Usage: get-local-token.sh <email> <password>}"

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_REGION=eu-central-1

POOL_ID=$(cd "$TF_LOCAL" && terraform output -raw cognito_user_pool_id)
CLIENT_ID=$(cd "$TF_LOCAL" && terraform output -raw cognito_client_id)

RESULT=$(aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --auth-parameters USERNAME="$EMAIL",PASSWORD="$PASSWORD" \
  --client-id "$CLIENT_ID" \
  --endpoint-url http://localhost:4566 \
  --region eu-central-1 \
  --output json)

ACCESS_TOKEN=$(echo "$RESULT" | jq -r '.AuthenticationResult.AccessToken')
ID_TOKEN=$(echo "$RESULT" | jq -r '.AuthenticationResult.IdToken')

if [[ "$ACCESS_TOKEN" == "null" ]]; then
  echo "Auth failed:" >&2
  echo "$RESULT" | jq . >&2
  exit 1
fi

# When eval'd: exports TOKEN into current shell
echo "export TOKEN='$ACCESS_TOKEN'"
echo "export ID_TOKEN='$ID_TOKEN'"

# Also print usage hint to stderr so it doesn't interfere with eval
cat >&2 <<EOF

Token obtained for $EMAIL

Usage with curl:
  curl -H "Authorization: Bearer \$TOKEN" http://localhost:8080/api/stations
  curl -H "Authorization: Bearer \$TOKEN" http://localhost:8080/api/reservations
EOF
