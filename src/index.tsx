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
  startForegroundService,
  stopForegroundService,
  initializeBluetoothManager
} from './beacon';

export * from './types';

export { default } from './beacon';
