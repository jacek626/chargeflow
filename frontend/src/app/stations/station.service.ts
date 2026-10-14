import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ChargingStationSummary, ChargingStationDetails } from './station.model';

@Injectable({ providedIn: 'root' })
export class StationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/stations`;

  listStations(): Observable<ChargingStationSummary[]> {
    return this.http.get<ChargingStationSummary[]>(this.base);
  }

  getStation(stationId: string): Observable<ChargingStationDetails> {
    return this.http.get<ChargingStationDetails>(`${this.base}/${stationId}`);
  }
}
