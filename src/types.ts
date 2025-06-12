export type Beacon = {
  uuid: string;
  major: string;
  minor: string;
  distance: number;
  rssi: number;
  txPower: number;
  bluetoothName?: string;
  bluetoothAddress?: string;
  manufacturer?: number;
  timestamp?: number;
}

export type OnBeaconsDetectedEvent = {
  beacons: Beacon[];
  uuid: string;
  identifier: string;
}

export type RegionEvent = {
  identifier: string;
  uuid: string;
  major?: string;
  minor?: string;
}

export type BeaconScanConfig = {
  major?: number;
  minor?: number;
  useForegroundService?: boolean;
  useBackgroundScanning?: boolean;
};

export type BeaconRadarEvent = 
  | "onBeaconsDetected"
  | "didEnterRegion"
  | "didExitRegion"
  | "onBluetoothStateChanged";