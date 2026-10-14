import { Component, OnInit, inject } from '@angular/core';
import { Router, ActivatedRoute } from '@angular/router';
import { AuthService } from '../auth.service';

@Component({
  standalone: true,
  selector: 'app-callback',
  template: `
    <div class="callback">
      @if (error) {
        <p class="error">Login failed: {{ error }}</p>
        <button (click)="retry()">Try again</button>
      } @else {
        <p>Signing in…</p>
      }
    </div>
  `,
  styles: [`
    .callback { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; gap: 1rem; }
    .error { color: #c0392b; }
  `],
})
export class CallbackComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  error: string | null = null;

  async ngOnInit(): Promise<void> {
    const code = this.route.snapshot.queryParamMap.get('code');
    const errorParam = this.route.snapshot.queryParamMap.get('error');

    if (errorParam) {
      this.error = this.route.snapshot.queryParamMap.get('error_description') ?? errorParam;
      return;
    }

    if (!code) {
      this.error = 'No authorization code in callback';
      return;
    }

    try {
      await this.auth.handleCallback(code);
      await this.router.navigate(['/stations']);
    } catch (e) {
      this.error = e instanceof Error ? e.message : 'Unexpected error';
    }
  }

  retry(): void {
    void this.auth.login();
  }
}
