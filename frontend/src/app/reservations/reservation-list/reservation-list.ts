import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ReservationService } from '../reservation.service';
import { Reservation } from '../reservation.model';

@Component({
  standalone: true,
  selector: 'app-reservation-list',
  imports: [DatePipe],
  template: `
    <div class="page">
      <h2>My Reservations</h2>
      @if (error()) {
        <p class="error">{{ error() }}</p>
      } @else if (loading()) {
        <p>Loading reservations…</p>
      } @else if (reservations().length === 0) {
        <p>You have no reservations yet.</p>
      } @else {
        <table class="res-table">
          <thead>
            <tr><th>Station</th><th>Point</th><th>Status</th><th>Created</th><th>Expires</th><th></th></tr>
          </thead>
          <tbody>
            @for (r of reservations(); track r.id) {
              <tr>
                <td>{{ r.stationName }}</td>
                <td>{{ r.chargingPointCode }}</td>
                <td><span [class]="'status status-' + r.status.toLowerCase()">{{ r.status }}</span></td>
                <td>{{ r.createdAt | date:'short' }}</td>
                <td>{{ r.expiresAt | date:'short' }}</td>
                <td>
                  @if (r.status === 'CREATED' || r.status === 'CONFIRMED') {
                    <button class="btn-cancel" (click)="cancel(r)" [disabled]="cancelling() === r.id">
                      {{ cancelling() === r.id ? 'Cancelling…' : 'Cancel' }}
                    </button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (cancelError()) {
        <p class="error">{{ cancelError() }}</p>
      }
    </div>
  `,
  styles: [`
    .page { padding: 1.5rem; max-width: 1000px; }
    h2 { margin-bottom: 1rem; }
    .error { color: #c0392b; }
    .res-table { width: 100%; border-collapse: collapse; }
    .res-table th, .res-table td { padding: 0.6rem 0.75rem; text-align: left; border-bottom: 1px solid #eee; }
    .res-table th { background: #f8f8f8; font-weight: 600; }
    .status { padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.8rem; font-weight: 600; }
    .status-created { background: #d4edda; color: #155724; }
    .status-confirmed { background: #b8ddc8; color: #0a4a2a; }
    .status-used { background: #cce5ff; color: #004085; }
    .status-cancelled { background: #e2e3e5; color: #383d41; }
    .status-expired { background: #f8d7da; color: #721c24; }
    .btn-cancel { padding: 0.25rem 0.7rem; background: #e74c3c; color: #fff; border: none; border-radius: 4px; cursor: pointer; font-size: 0.8rem; }
    .btn-cancel:disabled { opacity: 0.6; cursor: not-allowed; }
  `],
})
export class ReservationListComponent implements OnInit {
  private readonly service = inject(ReservationService);

  reservations = signal<Reservation[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);
  cancelling = signal<string | null>(null);
  cancelError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.service.listReservations().subscribe({
      next: (data) => { this.reservations.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load reservations.'); this.loading.set(false); },
    });
  }

  cancel(r: Reservation): void {
    this.cancelling.set(r.id);
    this.cancelError.set(null);
    this.service.cancelReservation(r.id).subscribe({
      next: () => { this.cancelling.set(null); this.load(); },
      error: (err) => {
        this.cancelError.set(err.status === 409
          ? 'Reservation cannot be cancelled in its current state.'
          : 'Cancellation failed. Please try again.');
        this.cancelling.set(null);
      },
    });
  }
}
