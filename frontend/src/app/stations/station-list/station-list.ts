import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { StationService } from '../station.service';
import { ChargingStationSummary } from '../station.model';

@Component({
  standalone: true,
  selector: 'app-station-list',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h2>Charging Stations</h2>
      @if (error()) {
        <p class="error">{{ error() }}</p>
      } @else if (loading()) {
        <p>Loading stations…</p>
      } @else if (stations().length === 0) {
        <p>No active stations found.</p>
      } @else {
        <ul class="station-list">
          @for (station of stations(); track station.id) {
            <li class="station-card">
              <a [routerLink]="['/stations', station.id]">
                <div class="station-name">{{ station.name }}</div>
                <div class="station-city">{{ station.city }}</div>
                <div class="station-availability">
                  {{ station.availableChargingPointCount }} / {{ station.chargingPointCount }} points available
                </div>
              </a>
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: [`
    .page { padding: 1.5rem; max-width: 800px; }
    h2 { margin-bottom: 1rem; }
    .error { color: #c0392b; }
    .station-list { list-style: none; padding: 0; display: grid; gap: 1rem; }
    .station-card a {
      display: block; padding: 1rem 1.25rem; border: 1px solid #ddd;
      border-radius: 8px; text-decoration: none; color: inherit;
      transition: box-shadow 0.15s;
    }
    .station-card a:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.12); }
    .station-name { font-weight: 600; font-size: 1.05rem; }
    .station-city { color: #666; font-size: 0.875rem; margin-top: 0.25rem; }
    .station-availability { margin-top: 0.5rem; font-size: 0.875rem; color: #27ae60; }
  `],
})
export class StationListComponent implements OnInit {
  private readonly service = inject(StationService);

  stations = signal<ChargingStationSummary[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);

  ngOnInit(): void {
    this.service.listStations().subscribe({
      next: (data) => { this.stations.set(data); this.loading.set(false); },
      error: () => { this.error.set('Failed to load stations.'); this.loading.set(false); },
    });
  }
}
