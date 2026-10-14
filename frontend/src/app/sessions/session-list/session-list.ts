import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { SessionService } from '../session.service';
import { ChargingSession } from '../session.model';

@Component({
  standalone: true,
  selector: 'app-session-list',
  imports: [DatePipe, DecimalPipe],
  template: `
    <div class="page">
      <h2>My Charging Sessions</h2>
      @if (error()) {
        <p class="error">{{ error() }}</p>
      } @else if (loading()) {
        <p>Loading sessions…</p>
      } @else if (sessions().length === 0) {
        <p>No charging sessions yet.</p>
      } @else {
        <table class="sessions-table">
          <thead>
            <tr><th>Station</th><th>Point</th><th>Status</th><th>Started</th><th>Energy</th><th>Cost</th><th></th></tr>
          </thead>
          <tbody>
            @for (s of sessions(); track s.id) {
              <tr>
                <td>{{ s.stationName }}</td>
                <td>{{ s.chargingPointCode }}</td>
                <td><span [class]="'status status-' + s.status.toLowerCase()">{{ s.status }}</span></td>
                <td>{{ s.startedAt | date:'short' }}</td>
                <td>{{ s.energyConsumedKwh !== null ? (s.energyConsumedKwh | number:'1.2-2') + ' kWh' : '—' }}</td>
                <td>{{ s.totalCost !== null ? (s.totalCost | number:'1.2-2') + ' PLN' : '—' }}</td>
                <td>
                  @if (s.status === 'STARTED' || s.status === 'CHARGING') {
                    <button class="btn-stop" (click)="stop(s)" [disabled]="stopping() === s.id">
                      {{ stopping() === s.id ? 'Stopping…' : 'Stop' }}
                    </button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (stopError()) { <p class="error">{{ stopError() }}</p> }
    </div>
  `,
  styles: [`
    .page { padding: 1.5rem; max-width: 1100px; }
    h2 { margin-bottom: 1rem; }
    .error { color: #c0392b; }
    .sessions-table { width: 100%; border-collapse: collapse; }
    .sessions-table th, .sessions-table td { padding: 0.6rem 0.75rem; text-align: left; border-bottom: 1px solid #eee; }
    .sessions-table th { background: #f8f8f8; font-weight: 600; }
    .status { padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.8rem; font-weight: 600; }
    .status-started { background: #cce5ff; color: #004085; }
    .status-charging { background: #b8ddc8; color: #0a4a2a; }
    .status-completed { background: #d4edda; color: #155724; }
    .status-failed { background: #f8d7da; color: #721c24; }
    .btn-stop { padding: 0.25rem 0.7rem; background: #e67e22; color: #fff; border: none; border-radius: 4px; cursor: pointer; font-size: 0.8rem; }
    .btn-stop:disabled { opacity: 0.6; cursor: not-allowed; }
  `],
})
export class SessionListComponent implements OnInit {
  private readonly service = inject(SessionService);

  sessions = signal<ChargingSession[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);
  stopping = signal<string | null>(null);
  stopError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.service.listSessions().subscribe({
      next: (data) => { this.sessions.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load sessions.'); this.loading.set(false); },
    });
  }

  stop(s: ChargingSession): void {
    this.stopping.set(s.id);
    this.stopError.set(null);
    this.service.stopSession(s.id).subscribe({
      next: () => { this.stopping.set(null); this.load(); },
      error: (err) => {
        this.stopError.set(err.status === 409
          ? 'Session is already stopped.'
          : 'Failed to stop session. Please try again.');
        this.stopping.set(null);
      },
    });
  }
}
