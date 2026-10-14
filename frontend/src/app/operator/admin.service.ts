import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChargingStationSummary, ChargingStationDetails, ChargingPoint } from '../stations/station.model';
import { Reservation } from '../reservations/reservation.model';
import { ChargingSession } from '../sessions/session.model';

export interface UpdatePointStatusRequest {
  status: string;
}

@Injectable({ providedIn: 'root' })
export class AdminService {
  private readonly http = inject(HttpClient);

  listStations(): Observable<ChargingStationSummary[]> {
    return this.http.get<ChargingStationSummary[]>('/api/admin/stations');
  }

  getStation(stationId: string): Observable<ChargingStationDetails> {
    return this.http.get<ChargingStationDetails>(`/api/admin/stations/${stationId}`);
  }

  listReservations(): Observable<Reservation[]> {
    return this.http.get<Reservation[]>('/api/admin/reservations');
  }

  listSessions(): Observable<ChargingSession[]> {
    return this.http.get<ChargingSession[]>('/api/admin/charging-sessions');
  }

  updatePointStatus(pointId: string, status: string): Observable<ChargingPoint> {
    return this.http.patch<ChargingPoint>(
      `/api/admin/charging-points/${pointId}/status`,
      { status } satisfies UpdatePointStatusRequest,
    );
  }
}
