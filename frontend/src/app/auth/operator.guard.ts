import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { environment } from '../../environments/environment';

export const operatorGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.isAuthenticated()) {
    if (environment.skipAuth) {
      try { await auth.localDevLogin('OPERATOR'); } catch { /* backend not running with local profile */ }
      return true;
    }
    void auth.login();
    return false;
  }
  const groups = auth.getUserClaims()?.['cognito:groups'] ?? [];
  if (groups.includes('OPERATOR')) return true;
  return router.createUrlTree(['/stations']);
};
