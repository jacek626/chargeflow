export type ReservationStatus = 'CREATED' | 'CONFIRMED' | 'USED' | 'CANCELLED' | 'EXPIRED';

export interface Reservation {
  id: string;
  customerId: string;
  chargingPointId: string;
  chargingPointCode: string;
  stationName: string;
  status: ReservationStatus;
  createdAt: string;
  expiresAt: string;
}

export interface CreateReservationRequest {
  chargingPointId: string;
}
