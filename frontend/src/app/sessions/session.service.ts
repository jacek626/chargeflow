import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { ChargingSession, StartSessionRequest } from './session.model';

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/charging-sessions`;

  listSessions(): Observable<ChargingSession[]> {
    return this.http.get<ChargingSession[]>(this.base);
  }

  getSession(sessionId: string): Observable<ChargingSession> {
    return this.http.get<ChargingSession>(`${this.base}/${sessionId}`);
  }

  startSession(request: StartSessionRequest): Observable<ChargingSession> {
    return this.http.post<ChargingSession>(this.base, request);
  }

  stopSession(sessionId: string): Observable<ChargingSession> {
    return this.http.post<ChargingSession>(`${this.base}/${sessionId}/stop`, {});
  }
}
