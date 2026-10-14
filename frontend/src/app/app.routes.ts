import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';
import { operatorGuard } from './auth/operator.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'stations', pathMatch: 'full' },
  {
    path: 'auth/callback',
    loadComponent: () => import('./auth/callback/callback').then(m => m.CallbackComponent),
  },
  {
    path: 'stations',
    loadComponent: () => import('./stations/station-list/station-list').then(m => m.StationListComponent),
  },
  {
    path: 'stations/:stationId',
    canActivate: [authGuard],
    loadComponent: () => import('./stations/station-detail/station-detail').then(m => m.StationDetailComponent),
  },
  {
    path: 'reservations',
    canActivate: [authGuard],
    loadComponent: () => import('./reservations/reservation-list/reservation-list').then(m => m.ReservationListComponent),
  },
  {
    path: 'sessions',
    canActivate: [authGuard],
    loadComponent: () => import('./sessions/session-list/session-list').then(m => m.SessionListComponent),
  },
  {
    path: 'admin',
    canActivate: [operatorGuard],
    loadComponent: () => import('./operator/operator-dashboard/operator-dashboard').then(m => m.OperatorDashboardComponent),
  },
  {
    path: 'admin/stations',
    canActivate: [operatorGuard],
    loadComponent: () => import('./operator/operator-stations/operator-stations').then(m => m.OperatorStationsComponent),
  },
  {
    path: 'admin/reservations',
    canActivate: [operatorGuard],
    loadComponent: () => import('./operator/operator-reservations/operator-reservations').then(m => m.OperatorReservationsComponent),
  },
  {
    path: 'admin/sessions',
    canActivate: [operatorGuard],
    loadComponent: () => import('./operator/operator-sessions/operator-sessions').then(m => m.OperatorSessionsComponent),
  },
  { path: '**', redirectTo: 'stations' },
];
