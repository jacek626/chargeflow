import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Reservation, CreateReservationRequest } from './reservation.model';

@Injectable({ providedIn: 'root' })
export class ReservationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/reservations`;

  listReservations(): Observable<Reservation[]> {
    return this.http.get<Reservation[]>(this.base);
  }

  getReservation(reservationId: string): Observable<Reservation> {
    return this.http.get<Reservation>(`${this.base}/${reservationId}`);
  }

  createReservation(request: CreateReservationRequest): Observable<Reservation> {
    return this.http.post<Reservation>(this.base, request);
  }

  cancelReservation(reservationId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${reservationId}`);
  }
}
