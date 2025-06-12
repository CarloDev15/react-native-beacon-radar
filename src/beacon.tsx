import { NativeModules, Platform, NativeEventEmitter } from "react-native";
import type { BeaconRadarEvent, OnBeaconsDetectedEvent, RegionEvent, BeaconScanConfig  } from './types';

const LINKING_ERROR =
  `The package 'react-native-beacon-radar' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: "" }) +
  "- You rebuilt the app after installing the package\n" +
  "- You are not using Expo Go\n";

const NativeBeaconRadar = NativeModules.BeaconRadar;

if (!NativeBeaconRadar) {
  throw new Error(LINKING_ERROR);
}

const beaconEmitter = new NativeEventEmitter(NativeBeaconRadar);


const BeaconRadar = {
  // Event listeners
  on: (event: BeaconRadarEvent, callback: ( data: OnBeaconsDetectedEvent | RegionEvent ) => void) => {
    return beaconEmitter.addListener(event, callback);
  },
  removeAllListeners: (event: BeaconRadarEvent) => {
    beaconEmitter.removeAllListeners(event);
  },

  // Android & iOS
  startScanning: (uuid: string, config: BeaconScanConfig ) => NativeBeaconRadar.startScanning(uuid, config),
  stopScanning: () => NativeBeaconRadar.stopScanning(),

  // iOS only
  requestAlwaysAuthorization: (): Promise<{ status: string }> => NativeBeaconRadar.requestAlwaysAuthorization(),
  requestWhenInUseAuthorization: (): Promise<{ status: string }> => NativeBeaconRadar.requestWhenInUseAuthorization(),
  getAuthorizationStatus: (): Promise<{ status: string }> => NativeBeaconRadar.getAuthorizationStatus(),

  // Bluetooth state (if implemented)
  isBluetoothEnabled: (): Promise<boolean> => NativeBeaconRadar.isBluetoothEnabled?.(),
  getBluetoothState: (): Promise<string> => NativeBeaconRadar.getBluetoothState?.(),

  // Android only
  startForegroundService: () => NativeBeaconRadar.startForegroundService?.(),
  stopForegroundService: () => NativeBeaconRadar.stopForegroundService?.(),
  initializeBluetoothManager: () => NativeBeaconRadar.initializeBluetoothManager?.(),
};

export const on = BeaconRadar.on;
export const removeAllListeners = BeaconRadar.removeAllListeners;
export const startScanning = BeaconRadar.startScanning;
export const stopScanning = BeaconRadar.stopScanning;
export const requestAlwaysAuthorization = BeaconRadar.requestAlwaysAuthorization;
export const requestWhenInUseAuthorization = BeaconRadar.requestWhenInUseAuthorization;
export const getAuthorizationStatus = BeaconRadar.getAuthorizationStatus;
export const isBluetoothEnabled = BeaconRadar.isBluetoothEnabled;
export const getBluetoothState = BeaconRadar.getBluetoothState;
export const startForegroundService = BeaconRadar.startForegroundService;
export const stopForegroundService = BeaconRadar.stopForegroundService;
export const initializeBluetoothManager = BeaconRadar.initializeBluetoothManager;

export default BeaconRadar;