export type SessionStatus = 'STARTED' | 'CHARGING' | 'COMPLETED' | 'FAILED';

export interface ChargingSession {
  id: string;
  customerId: string;
  reservationId: string;
  chargingPointId: string;
  chargingPointCode: string;
  stationName: string;
  status: SessionStatus;
  startedAt: string;
  stoppedAt: string | null;
  energyConsumedKwh: number | null;
  totalCost: number | null;
}

export interface StartSessionRequest {
  reservationId: string;
}
