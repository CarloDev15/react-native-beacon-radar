export {
  on,
  removeAllListeners,
  startScanning,
  stopScanning,
  requestAlwaysAuthorization,
  requestWhenInUseAuthorization,
  getAuthorizationStatus,
  isBluetoothEnabled,
  getBluetoothState,
  getLocationState,
  getBluetoothAndLocationState,
  startForegroundService,
  stopForegroundService,
  initializeBluetoothManager
} from './beacon';

export * from './types';

export { default } from './beacon';