export interface ChargingStationSummary {
  id: string;
  name: string;
  city: string;
  active: boolean;
  chargingPointCount: number;
  availableChargingPointCount: number;
}

export interface ChargingStationDetails {
  id: string;
  name: string;
  address: string;
  city: string;
  latitude: number;
  longitude: number;
  active: boolean;
  chargingPoints: ChargingPoint[];
}

export interface ChargingPoint {
  id: string;
  code: string;
  connectorType: string;
  maxPowerKw: number;
  pricePerKwh: number;
  status: 'AVAILABLE' | 'RESERVED' | 'CHARGING' | 'OUT_OF_SERVICE';
  active: boolean;
}
