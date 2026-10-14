import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminService } from '../admin.service';
import { ChargingStationSummary } from '../../stations/station.model';
import { Reservation } from '../../reservations/reservation.model';
import { ChargingSession } from '../../sessions/session.model';

@Component({
  standalone: true,
  selector: 'app-operator-dashboard',
  imports: [RouterLink],
  template: `
    <div class="dashboard">
      <h1>Operator Dashboard</h1>

      @if (loading()) {
        <p class="loading">Loading...</p>
      } @else {
        <div class="stats-grid">
          <div class="stat-card">
            <div class="stat-value">{{ stations().length }}</div>
            <div class="stat-label">Total Stations</div>
            <div class="stat-sub">{{ activeStations() }} active</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ totalPoints() }}</div>
            <div class="stat-label">Charging Points</div>
            <div class="stat-sub">{{ availablePoints() }} available</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ reservations().length }}</div>
            <div class="stat-label">Reservations</div>
            <div class="stat-sub">{{ activeReservations() }} active</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ sessions().length }}</div>
            <div class="stat-label">Sessions</div>
            <div class="stat-sub">{{ activeSessions() }} active</div>
          </div>
        </div>

        <div class="quick-links">
          <a class="link-card" routerLink="/admin/stations">Manage Stations & Points</a>
          <a class="link-card" routerLink="/admin/reservations">View Reservations</a>
          <a class="link-card" routerLink="/admin/sessions">View Sessions</a>
        </div>
      }
    </div>
  `,
  styles: [`
    .dashboard { padding: 2rem; }
    h1 { margin-bottom: 2rem; }
    .loading { color: #888; }
    .stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 1rem; margin-bottom: 2rem; }
    .stat-card { background: #f5f5f5; border-radius: 8px; padding: 1.5rem; text-align: center; }
    .stat-value { font-size: 2.5rem; font-weight: 700; color: #1a1a2e; }
    .stat-label { font-size: 0.875rem; color: #555; margin-top: 0.25rem; }
    .stat-sub { font-size: 0.75rem; color: #4fc3f7; margin-top: 0.25rem; }
    .quick-links { display: flex; gap: 1rem; flex-wrap: wrap; }
    .link-card { display: block; padding: 1rem 1.5rem; background: #1a1a2e; color: #4fc3f7; border-radius: 8px; text-decoration: none; font-weight: 600; }
    .link-card:hover { background: #2a2a4e; }
    @media (prefers-color-scheme: dark) {
      .stat-card { background: #2a2a2a; }
      .stat-value { color: #eee; }
    }
    :root[data-theme="dark"] .stat-card { background: #2a2a2a; }
    :root[data-theme="dark"] .stat-value { color: #eee; }
  `],
})
export class OperatorDashboardComponent implements OnInit {
  private readonly adminService = inject(AdminService);

  stations = signal<ChargingStationSummary[]>([]);
  reservations = signal<Reservation[]>([]);
  sessions = signal<ChargingSession[]>([]);
  loading = signal(true);

  activeStations = computed(() => this.stations().filter(s => s.active).length);
  totalPoints = computed(() => this.stations().reduce((s, st) => s + st.chargingPointCount, 0));
  availablePoints = computed(() => this.stations().reduce((s, st) => s + st.availableChargingPointCount, 0));
  activeReservations = computed(() => this.reservations().filter(r => r.status === 'ACTIVE').length);
  activeSessions = computed(() => this.sessions().filter(s => s.status === 'ACTIVE').length);

  ngOnInit(): void {
    let pending = 3;
    const done = () => { if (--pending === 0) this.loading.set(false); };

    this.adminService.listStations().subscribe({ next: d => { this.stations.set(d); done(); }, error: () => done() });
    this.adminService.listReservations().subscribe({ next: d => { this.reservations.set(d); done(); }, error: () => done() });
    this.adminService.listSessions().subscribe({ next: d => { this.sessions.set(d); done(); }, error: () => done() });
  }
}
