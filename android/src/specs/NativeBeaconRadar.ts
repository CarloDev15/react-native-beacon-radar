import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  startScanning(uuid: string, options: { major: string, minor: string, useForegroundService: boolean }): Promise<void>;
  stopRanging(region: Object): void;
  startRanging(region: Object): void;
  removeAllListeners(event: string): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('BeaconRadar');