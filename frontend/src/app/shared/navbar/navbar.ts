import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../auth/auth.service';

@Component({
  standalone: true,
  selector: 'app-navbar',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="navbar">
      <a class="brand" routerLink="/stations">ChargeFlow</a>
      <ul class="nav-links">
        <li><a routerLink="/stations" routerLinkActive="active">Stations</a></li>
        <li><a routerLink="/reservations" routerLinkActive="active">Reservations</a></li>
        <li><a routerLink="/sessions" routerLinkActive="active">Sessions</a></li>
        @if (isOperator) {
          <li class="separator">|</li>
          <li><a routerLink="/admin" routerLinkActive="active">Dashboard</a></li>
          <li><a routerLink="/admin/stations" routerLinkActive="active">Manage</a></li>
        }
      </ul>
      <div class="user-area">
        @if (auth.isAuthenticated()) {
          <span class="email">{{ email }}</span>
          <button class="btn-logout" (click)="logout()">Log out</button>
        } @else {
          <button class="btn-login" (click)="login()">Log in</button>
        }
      </div>
    </nav>
  `,
  styles: [`
    .navbar {
      display: flex;
      align-items: center;
      gap: 2rem;
      padding: 0.75rem 1.5rem;
      background: #1a1a2e;
      color: #fff;
    }
    .brand { color: #4fc3f7; font-weight: 700; font-size: 1.2rem; text-decoration: none; }
    .nav-links { display: flex; gap: 1.5rem; list-style: none; margin: 0; padding: 0; flex: 1; }
    .nav-links a { color: #ccc; text-decoration: none; }
    .nav-links a.active { color: #4fc3f7; font-weight: 600; }
    .user-area { display: flex; align-items: center; gap: 1rem; }
    .separator { color: #555; padding: 0 0.25rem; }
    .email { font-size: 0.875rem; color: #aaa; }
    button { padding: 0.4rem 1rem; border: none; border-radius: 4px; cursor: pointer; font-size: 0.875rem; }
    .btn-login { background: #4fc3f7; color: #000; }
    .btn-logout { background: transparent; border: 1px solid #aaa; color: #ccc; }
  `],
})
export class NavbarComponent {
  readonly auth = inject(AuthService);

  get email(): string {
    return this.auth.getUserClaims()?.email ?? '';
  }

  get isOperator(): boolean {
    return this.auth.getUserClaims()?.['cognito:groups']?.includes('OPERATOR') ?? false;
  }

  login(): void { void this.auth.login(); }
  logout(): void { this.auth.logout(); }
}
