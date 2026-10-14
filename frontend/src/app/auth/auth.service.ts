import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { environment } from '../../environments/environment';

interface TokenSet {
  accessToken: string;
  idToken: string;
  expiresAt: number; // epoch ms
}

interface TokenResponse {
  access_token: string;
  id_token: string;
  expires_in: number;
  token_type: string;
  refresh_token?: string;
}

interface IdTokenClaims {
  sub: string;
  email: string;
  'cognito:groups'?: string[];
}

const STORAGE_KEY = 'cf_tokens';
const VERIFIER_KEY = 'cf_pkce_verifier';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly router = inject(Router);
  private readonly cfg = environment.auth;
  private readonly baseUrl = `https://${this.cfg.cognitoDomain}.auth.${this.cfg.region}.amazoncognito.com`;

  // --- Public API ---

  isAuthenticated(): boolean {
    const tokens = this.loadTokens();
    return tokens !== null && Date.now() < tokens.expiresAt;
  }

  getAccessToken(): string | null {
    const tokens = this.loadTokens();
    if (!tokens || Date.now() >= tokens.expiresAt) return null;
    return tokens.accessToken;
  }

  getUserClaims(): IdTokenClaims | null {
    const tokens = this.loadTokens();
    if (!tokens) return null;
    return parseJwtPayload<IdTokenClaims>(tokens.idToken);
  }

  async localDevLogin(role: 'CUSTOMER' | 'OPERATOR' = 'CUSTOMER'): Promise<void> {
    const res = await fetch(`${environment.apiBaseUrl}/dev/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `role=${role}&email=local-${role.toLowerCase()}@chargeflow.local`,
    });
    if (!res.ok) throw new Error(`Dev token request failed: ${res.status}`);
    const tokens: TokenResponse = { ...(await res.json() as object), token_type: 'Bearer' } as TokenResponse;
    this.storeTokens(tokens);
  }

  async login(): Promise<void> {
    const verifier = generateCodeVerifier();
    const challenge = await generateCodeChallenge(verifier);
    sessionStorage.setItem(VERIFIER_KEY, verifier);

    const params = new URLSearchParams({
      response_type: 'code',
      client_id: this.cfg.clientId,
      redirect_uri: this.cfg.redirectUri,
      scope: this.cfg.scopes,
      code_challenge: challenge,
      code_challenge_method: 'S256',
    });
    window.location.href = `${this.baseUrl}/oauth2/authorize?${params}`;
  }

  async handleCallback(code: string): Promise<void> {
    const verifier = sessionStorage.getItem(VERIFIER_KEY);
    if (!verifier) throw new Error('PKCE verifier missing — start login again');

    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: this.cfg.clientId,
      redirect_uri: this.cfg.redirectUri,
      code,
      code_verifier: verifier,
    });

    const response = await fetch(`${this.baseUrl}/oauth2/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    });

    if (!response.ok) {
      const err = await response.text();
      throw new Error(`Token exchange failed: ${err}`);
    }

    const tokens: TokenResponse = await response.json();
    this.storeTokens(tokens);
    sessionStorage.removeItem(VERIFIER_KEY);
  }

  logout(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    const params = new URLSearchParams({
      client_id: this.cfg.clientId,
      logout_uri: this.cfg.postLogoutUri,
    });
    window.location.href = `${this.baseUrl}/logout?${params}`;
  }

  // --- Private helpers ---

  private storeTokens(response: TokenResponse): void {
    const set: TokenSet = {
      accessToken: response.access_token,
      idToken: response.id_token,
      expiresAt: Date.now() + response.expires_in * 1000 - 30_000, // 30 s buffer
    };
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(set));
  }

  private loadTokens(): TokenSet | null {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as TokenSet;
    } catch {
      return null;
    }
  }
}

// --- PKCE helpers (Web Crypto API) ---

function generateCodeVerifier(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

async function generateCodeChallenge(verifier: string): Promise<string> {
  const encoded = new TextEncoder().encode(verifier);
  const hash = await crypto.subtle.digest('SHA-256', encoded);
  return base64UrlEncode(new Uint8Array(hash));
}

function base64UrlEncode(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '');
}

function parseJwtPayload<T>(token: string): T | null {
  try {
    const payload = token.split('.')[1];
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))) as T;
  } catch {
    return null;
  }
}
