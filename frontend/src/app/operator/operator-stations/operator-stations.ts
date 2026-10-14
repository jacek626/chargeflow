import { Component, OnInit, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { AdminService } from '../admin.service';
import { ChargingStationSummary, ChargingStationDetails, ChargingPoint } from '../../stations/station.model';

@Component({
  standalone: true,
  selector: 'app-operator-stations',
  imports: [DecimalPipe],
  template: `
    <div class="container">
      <h1>Stations & Charging Points</h1>

      @if (loading()) {
        <p class="loading">Loading...</p>
      } @else if (error()) {
        <p class="error">{{ error() }}</p>
      } @else {
        <div class="station-list">
          @for (station of stations(); track station.id) {
            <div class="station-card">
              <div class="station-header" (click)="toggleStation(station.id)" style="cursor:pointer">
                <div>
                  <strong>{{ station.name }}</strong>
                  <span class="city">{{ station.city }}</span>
                  <span class="badge" [class.inactive]="!station.active">{{ station.active ? 'Active' : 'Inactive' }}</span>
                </div>
                <div class="station-counts">
                  {{ station.availableChargingPointCount }}/{{ station.chargingPointCount }} available
                </div>
              </div>

              @if (expandedStation() === station.id) {
                @if (loadingPoints()[station.id]) {
                  <p class="loading-pts">Loading points...</p>
                } @else if (stationDetails()[station.id]; as details) {
                  <table class="points-table">
                    <thead>
                      <tr>
                        <th>Code</th><th>Type</th><th>Power</th><th>Price</th><th>Status</th><th>Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (pt of details.chargingPoints; track pt.id) {
                        <tr>
                          <td>{{ pt.code }}</td>
                          <td>{{ pt.connectorType }}</td>
                          <td>{{ pt.maxPowerKw }} kW</td>
                          <td>{{ pt.pricePerKwh | number:'1.2-2' }} PLN/kWh</td>
                          <td><span class="status-badge status-{{ pt.status.toLowerCase() }}">{{ pt.status }}</span></td>
                          <td>
                            @if (pt.status === 'AVAILABLE') {
                              <button class="btn-oos" (click)="changeStatus(station.id, pt, 'OUT_OF_SERVICE')">Mark OOS</button>
                            } @else if (pt.status === 'OUT_OF_SERVICE') {
                              <button class="btn-avail" (click)="changeStatus(station.id, pt, 'AVAILABLE')">Restore</button>
                            }
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                }
              }
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .container { padding: 2rem; }
    h1 { margin-bottom: 1.5rem; }
    .loading, .loading-pts { color: #888; padding: 0.5rem 0; }
    .error { color: #c00; }
    .station-card { border: 1px solid #ddd; border-radius: 8px; margin-bottom: 1rem; overflow: hidden; }
    .station-header { display: flex; justify-content: space-between; align-items: center; padding: 1rem 1.25rem; background: #f9f9f9; }
    .city { color: #666; font-size: 0.875rem; margin-left: 0.75rem; }
    .badge { margin-left: 0.75rem; padding: 0.2rem 0.5rem; border-radius: 4px; font-size: 0.75rem; background: #4caf50; color: #fff; }
    .badge.inactive { background: #999; }
    .station-counts { font-size: 0.875rem; color: #555; }
    .points-table { width: 100%; border-collapse: collapse; font-size: 0.875rem; }
    .points-table th { background: #f0f0f0; text-align: left; padding: 0.5rem 0.75rem; border-bottom: 1px solid #ddd; }
    .points-table td { padding: 0.5rem 0.75rem; border-bottom: 1px solid #eee; }
    .status-badge { padding: 0.15rem 0.5rem; border-radius: 4px; font-size: 0.75rem; font-weight: 600; }
    .status-available { background: #c8e6c9; color: #1b5e20; }
    .status-reserved { background: #fff3e0; color: #e65100; }
    .status-charging { background: #e3f2fd; color: #0d47a1; }
    .status-out_of_service { background: #fce4ec; color: #880e4f; }
    button { padding: 0.3rem 0.75rem; border: none; border-radius: 4px; cursor: pointer; font-size: 0.8rem; }
    .btn-oos { background: #ff7043; color: #fff; }
    .btn-avail { background: #4caf50; color: #fff; }
    @media (prefers-color-scheme: dark) {
      .station-header { background: #2a2a2a; }
      .points-table th { background: #333; }
    }
    :root[data-theme="dark"] .station-header { background: #2a2a2a; }
    :root[data-theme="dark"] .points-table th { background: #333; }
  `],
})
export class OperatorStationsComponent implements OnInit {
  private readonly adminService = inject(AdminService);

  stations = signal<ChargingStationSummary[]>([]);
  stationDetails = signal<Record<string, ChargingStationDetails>>({});
  loadingPoints = signal<Record<string, boolean>>({});
  expandedStation = signal<string | null>(null);
  loading = signal(true);
  error = signal('');

  ngOnInit(): void {
    this.adminService.listStations().subscribe({
      next: data => { this.stations.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load stations.'); this.loading.set(false); },
    });
  }

  toggleStation(stationId: string): void {
    if (this.expandedStation() === stationId) {
      this.expandedStation.set(null);
      return;
    }
    this.expandedStation.set(stationId);
    if (!this.stationDetails()[stationId]) {
      this.loadingPoints.update(prev => ({ ...prev, [stationId]: true }));
      this.adminService.getStation(stationId).subscribe({
        next: d => {
          this.stationDetails.update(prev => ({ ...prev, [stationId]: d }));
          this.loadingPoints.update(prev => ({ ...prev, [stationId]: false }));
        },
        error: () => this.loadingPoints.update(prev => ({ ...prev, [stationId]: false })),
      });
    }
  }

  changeStatus(stationId: string, point: ChargingPoint, newStatus: string): void {
    this.adminService.updatePointStatus(point.id, newStatus).subscribe({
      next: updated => {
        this.stationDetails.update(prev => {
          const details = prev[stationId];
          if (!details) return prev;
          return {
            ...prev,
            [stationId]: {
              ...details,
              chargingPoints: details.chargingPoints.map(p => p.id === updated.id ? updated : p),
            },
          };
        });
      },
      error: () => alert('Status change failed. The point may be reserved or in use.'),
    });
  }
}
