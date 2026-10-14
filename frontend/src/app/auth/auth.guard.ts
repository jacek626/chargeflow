import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { environment } from '../../environments/environment';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  if (auth.isAuthenticated()) return true;
  if (environment.skipAuth) {
    try { await auth.localDevLogin('CUSTOMER'); } catch { /* backend not running with local profile */ }
    return true;
  }
  void auth.login();
  return false;
};
