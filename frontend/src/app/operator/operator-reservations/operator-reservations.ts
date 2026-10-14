import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, SlicePipe } from '@angular/common';
import { AdminService } from '../admin.service';
import { Reservation } from '../../reservations/reservation.model';

@Component({
  standalone: true,
  selector: 'app-operator-reservations',
  imports: [DatePipe, SlicePipe],
  template: `
    <div class="container">
      <h1>All Reservations</h1>
      @if (loading()) {
        <p class="loading">Loading...</p>
      } @else if (error()) {
        <p class="error">{{ error() }}</p>
      } @else if (reservations().length === 0) {
        <p class="empty">No reservations found.</p>
      } @else {
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Customer</th>
                <th>Station</th>
                <th>Point</th>
                <th>Status</th>
                <th>Reserved At</th>
                <th>Valid Until</th>
              </tr>
            </thead>
            <tbody>
              @for (r of reservations(); track r.id) {
                <tr>
                  <td class="mono">{{ r.customerId | slice:0:16 }}…</td>
                  <td>{{ r.stationName }}</td>
                  <td>{{ r.chargingPointCode }}</td>
                  <td><span class="status status-{{ r.status.toLowerCase() }}">{{ r.status }}</span></td>
                  <td>{{ r.createdAt | date:'short' }}</td>
                  <td>{{ r.expiresAt | date:'short' }}</td>
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
    .status-used { background: #e3f2fd; color: #0d47a1; }
    .status-cancelled { background: #eeeeee; color: #555; }
    .status-expired { background: #fce4ec; color: #880e4f; }
    @media (prefers-color-scheme: dark) { th { background: #333; } }
    :root[data-theme="dark"] th { background: #333; }
  `],
})
export class OperatorReservationsComponent implements OnInit {
  private readonly adminService = inject(AdminService);

  reservations = signal<Reservation[]>([]);
  loading = signal(true);
  error = signal('');

  ngOnInit(): void {
    this.adminService.listReservations().subscribe({
      next: data => { this.reservations.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load reservations.'); this.loading.set(false); },
    });
  }
}
