import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { DecimalPipe } from '@angular/common';
import { StationService } from '../station.service';
import { ReservationService } from '../../reservations/reservation.service';
import { ChargingStationDetails, ChargingPoint } from '../station.model';

@Component({
  standalone: true,
  selector: 'app-station-detail',
  imports: [DecimalPipe],
  template: `
    <div class="page">
      @if (error()) {
        <p class="error">{{ error() }}</p>
      } @else if (loading()) {
        <p>Loading station…</p>
      } @else if (station(); as s) {
        <h2>{{ s.name }}</h2>
        <p class="address">{{ s.address }}, {{ s.city }}</p>
        <h3>Charging Points</h3>
        @if (s.chargingPoints.length === 0) {
          <p>No active charging points.</p>
        } @else {
          <table class="points-table">
            <thead>
              <tr>
                <th>Code</th><th>Type</th><th>Power</th><th>Price</th><th>Status</th><th></th>
              </tr>
            </thead>
            <tbody>
              @for (point of s.chargingPoints; track point.id) {
                <tr>
                  <td>{{ point.code }}</td>
                  <td>{{ point.connectorType }}</td>
                  <td>{{ point.maxPowerKw }} kW</td>
                  <td>{{ point.pricePerKwh | number:'1.2-2' }} PLN/kWh</td>
                  <td><span [class]="'status status-' + point.status.toLowerCase()">{{ point.status }}</span></td>
                  <td>
                    @if (point.status === 'AVAILABLE') {
                      <button class="btn-reserve" (click)="reserve(point)" [disabled]="reserving() === point.id">
                        {{ reserving() === point.id ? 'Reserving…' : 'Reserve' }}
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
        @if (reserveError()) {
          <p class="error">{{ reserveError() }}</p>
        }
      }
    </div>
  `,
  styles: [`
    .page { padding: 1.5rem; max-width: 900px; }
    .address { color: #666; margin-bottom: 1.5rem; }
    .error { color: #c0392b; }
    .points-table { width: 100%; border-collapse: collapse; margin-top: 0.5rem; }
    .points-table th, .points-table td { padding: 0.6rem 0.75rem; text-align: left; border-bottom: 1px solid #eee; }
    .points-table th { font-weight: 600; background: #f8f8f8; }
    .status { padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.8rem; font-weight: 600; }
    .status-available { background: #d4edda; color: #155724; }
    .status-reserved { background: #fff3cd; color: #856404; }
    .status-charging { background: #cce5ff; color: #004085; }
    .status-out_of_service { background: #f8d7da; color: #721c24; }
    .btn-reserve { padding: 0.3rem 0.75rem; background: #4fc3f7; border: none; border-radius: 4px; cursor: pointer; font-size: 0.85rem; }
    .btn-reserve:disabled { opacity: 0.6; cursor: not-allowed; }
  `],
})
export class StationDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly stationService = inject(StationService);
  private readonly reservationService = inject(ReservationService);

  station = signal<ChargingStationDetails | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  reserving = signal<string | null>(null);
  reserveError = signal<string | null>(null);

  ngOnInit(): void {
    const stationId = this.route.snapshot.paramMap.get('stationId')!;
    this.stationService.getStation(stationId).subscribe({
      next: (data) => { this.station.set(data); this.loading.set(false); },
      error: () => { this.error.set('Station not found.'); this.loading.set(false); },
    });
  }

  reserve(point: ChargingPoint): void {
    this.reserving.set(point.id);
    this.reserveError.set(null);
    this.reservationService.createReservation({ chargingPointId: point.id }).subscribe({
      next: () => void this.router.navigate(['/reservations']),
      error: (err) => {
        this.reserveError.set(err.status === 409
          ? 'This charging point is no longer available.'
          : 'Reservation failed. Please try again.');
        this.reserving.set(null);
      },
    });
  }
}
