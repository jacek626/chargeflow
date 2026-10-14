// Local development defaults.
// After running `terraform apply` in infrastructure/environments/local,
// replace the placeholder values with outputs from `terraform output`.
export const environment = {
  production: false,
  // Set to true to bypass frontend auth guards when Cognito is not available locally.
  // Protected API endpoints still require a valid JWT from the backend.
  skipAuth: true,
  apiBaseUrl: 'http://localhost:8080',
  auth: {
    // From: terraform output cognito_client_id
    clientId: 'REPLACE_WITH_COGNITO_CLIENT_ID',
    // Cognito hosted UI: https://<domain>.auth.<region>.amazoncognito.com
    cognitoDomain: 'REPLACE_WITH_COGNITO_DOMAIN',
    region: 'eu-central-1',
    redirectUri: 'http://localhost:4201/auth/callback',
    postLogoutUri: 'http://localhost:4201',
    scopes: 'openid email profile',
  },
};
