import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, SlicePipe } from '@angular/common';
import { AdminService } from '../admin.service';
import { ChargingSession } from '../../sessions/session.model';

@Component({
  standalone: true,
  selector: 'app-operator-sessions',
  imports: [DatePipe, DecimalPipe, SlicePipe],
  template: `
    <div class="container">
      <h1>All Charging Sessions</h1>
      @if (loading()) {
        <p class="loading">Loading...</p>
      } @else if (error()) {
        <p class="error">{{ error() }}</p>
      } @else if (sessions().length === 0) {
        <p class="empty">No sessions found.</p>
      } @else {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Customer</th>
                <th>Station</th>
                <th>Point</th>
                <th>Status</th>
                <th>Started At</th>
                <th>Stopped At</th>
                <th>Energy (kWh)</th>
                <th>Cost (PLN)</th>
              </tr>
            </thead>
            <tbody>
              @for (s of sessions(); track s.id) {
                <tr>
                  <td class="mono">{{ s.customerId | slice:0:16 }}…</td>
                  <td>{{ s.stationName }}</td>
                  <td>{{ s.chargingPointCode }}</td>
                  <td><span class="status status-{{ s.status.toLowerCase() }}">{{ s.status }}</span></td>
                  <td>{{ s.startedAt | date:'short' }}</td>
                  <td>{{ s.stoppedAt ? (s.stoppedAt | date:'short') : '—' }}</td>
                  <td>{{ s.energyConsumedKwh != null ? (s.energyConsumedKwh | number:'1.2-4') : '—' }}</td>
                  <td>{{ s.totalCost != null ? (s.totalCost | number:'1.2-2') : '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
  styles: [`
    .container { padding: 2rem; }
    h1 { margin-bottom: 1.5rem; }
    .loading, .empty { color: #888; }
    .error { color: #c00; }
    .table-wrap { overflow-x: auto; }
    table { width: 100%; border-collapse: collapse; font-size: 0.875rem; }
    th { background: #f0f0f0; text-align: left; padding: 0.5rem 0.75rem; border-bottom: 2px solid #ddd; }
    td { padding: 0.5rem 0.75rem; border-bottom: 1px solid #eee; }
    .mono { font-family: monospace; font-size: 0.8rem; }
    .status { padding: 0.15rem 0.5rem; border-radius: 4px; font-size: 0.75rem; font-weight: 600; }
    .status-active { background: #c8e6c9; color: #1b5e20; }
    .status-completed { background: #e3f2fd; color: #0d47a1; }
    .status-failed { background: #fce4ec; color: #880e4f; }
    @media (prefers-color-scheme: dark) { th { background: #333; } }
    :root[data-theme="dark"] th { background: #333; }
  `],
})
export class OperatorSessionsComponent implements OnInit {
  private readonly adminService = inject(AdminService);

  sessions = signal<ChargingSession[]>([]);
  loading = signal(true);
  error = signal('');

  ngOnInit(): void {
    this.adminService.listSessions().subscribe({
      next: data => { this.sessions.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load sessions.'); this.loading.set(false); },
    });
  }
}
