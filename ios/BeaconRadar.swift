import CoreLocation
import CoreBluetooth
import React

@objc(BeaconRadar)
class BeaconRadar: NSObject, RCTBridgeModule, CLLocationManagerDelegate {
    
    static func moduleName() -> String {
        return "BeaconRadar"
    }
    
    private var locationManager: CLLocationManager!
    private var beaconRegion: CLBeaconRegion!
    public var bridge: RCTBridge!
    
    // React Native bridge setup
    @objc static func requiresMainQueueSetup() -> Bool {
        return true
    }
    
    // MARK: - Public Methods
    
    @objc func startScanning(_ uuid: String, config: NSDictionary) {
        print("[BeaconRadar] Starting scanning for UUID: \(uuid)")
        
        DispatchQueue.main.async {
            // Initialize location manager
            if self.locationManager == nil {
                self.locationManager = CLLocationManager()
                self.locationManager.delegate = self
                self.locationManager.desiredAccuracy = kCLLocationAccuracyBest
            }
            
            // Configure background scanning if needed
            if let useBackgroundScanning = config["useBackgroundScanning"] as? Bool, useBackgroundScanning {
                self.locationManager.allowsBackgroundLocationUpdates = true
                self.locationManager.pausesLocationUpdatesAutomatically = false
                print("[BeaconRadar] Background scanning enabled")
            }
            
            // Create beacon region
            guard let proximityUUID = UUID(uuidString: uuid) else {
                print("[BeaconRadar] Error: Invalid UUID format")
                return
            }
            
            if let majorStr = config["major"] as? String,
               let minorStr = config["minor"] as? String,
               let major = CLBeaconMajorValue(majorStr),
               let minor = CLBeaconMinorValue(minorStr) {
                
                self.beaconRegion = CLBeaconRegion(
                    uuid: proximityUUID,
                    major: major,
                    minor: minor,
                    identifier: "RNIbeaconScannerRegion"
                )
                print("[BeaconRadar] Created region with UUID: \(uuid), major: \(major), minor: \(minor)")
            } else {
                self.beaconRegion = CLBeaconRegion(
                    uuid: proximityUUID,
                    identifier: "RNIbeaconScannerRegion"
                )
                print("[BeaconRadar] Created region with UUID: \(uuid) (no major/minor)")
            }
            
            // Configure region notifications
            self.beaconRegion.notifyOnEntry = true
            self.beaconRegion.notifyOnExit = true
            self.beaconRegion.notifyEntryStateOnDisplay = true
            
            // Check and request authorization
            let status = CLLocationManager.authorizationStatus()
            print("[BeaconRadar] Current authorization status: \(self.stringFromAuthorizationStatus(status))")
            
            if status == .notDetermined {
                print("[BeaconRadar] Requesting always authorization")
                self.locationManager.requestAlwaysAuthorization()
            } else if status == .authorizedAlways || status == .authorizedWhenInUse {
                print("[BeaconRadar] Already authorized, starting monitoring")
                self.startMonitoringAndRanging()
            } else {
                print("[BeaconRadar] Location services not authorized")
                self.sendEvent(name: "onAuthorizationFailure", body: ["status": self.stringFromAuthorizationStatus(status)])
            }
        }
    }
    
    @objc func stopScanning() {
        print("[BeaconRadar] Stopping scanning")
        guard let beaconRegion = self.beaconRegion else { return }
        
        self.locationManager.stopMonitoring(for: beaconRegion)
        self.locationManager.stopRangingBeacons(in: beaconRegion)
        self.locationManager.allowsBackgroundLocationUpdates = false
    }
    
    @objc func requestAlwaysAuthorization(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async {
            let status = CLLocationManager.authorizationStatus()
            if status == .notDetermined {
                self.locationManager.requestAlwaysAuthorization()
            }
            resolve(["status": self.stringFromAuthorizationStatus(status)])
        }
    }
    
    @objc func requestWhenInUseAuthorization(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async {
            let status = CLLocationManager.authorizationStatus()
            if status == .notDetermined {
                self.locationManager.requestWhenInUseAuthorization()
            }
            resolve(["status": self.stringFromAuthorizationStatus(status)])
        }
    }
    
    @objc func getAuthorizationStatus(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        DispatchQueue.main.async {
            let status = CLLocationManager.authorizationStatus()
            resolve(self.stringFromAuthorizationStatus(status))
        }
    }
    
    // MARK: - Private Methods
    
    private func startMonitoringAndRanging() {
        guard let beaconRegion = self.beaconRegion else { return }
        
        print("[BeaconRadar] Starting monitoring and ranging for region: \(beaconRegion.identifier)")
        
        // First stop any existing monitoring
        self.locationManager.stopMonitoring(for: beaconRegion)
        self.locationManager.stopRangingBeacons(in: beaconRegion)
        
        // Start new monitoring
        self.locationManager.startMonitoring(for: beaconRegion)
        self.locationManager.startRangingBeacons(in: beaconRegion)
        
        // Request immediate state check
        self.locationManager.requestState(for: beaconRegion)
    }
    
    private func sendEvent(name: String, body: Any?) {
        self.bridge.eventDispatcher().sendAppEvent(withName: name, body: body)
    }
    
    private func stringFromAuthorizationStatus(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: return "notDetermined"
        case .restricted: return "restricted"
        case .denied: return "denied"
        case .authorizedAlways: return "authorizedAlways"
        case .authorizedWhenInUse: return "authorizedWhenInUse"
        @unknown default: return "unknown"
        }
    }
    
    // MARK: - CLLocationManagerDelegate
    
    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        print("[BeaconRadar] Authorization status changed: \(self.stringFromAuthorizationStatus(status))")
        
        if status == .authorizedAlways || status == .authorizedWhenInUse {
            self.startMonitoringAndRanging()
        } else {
            self.sendEvent(name: "onAuthorizationFailure", body: ["status": self.stringFromAuthorizationStatus(status)])
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        
        let stateStr: String
        switch state {
        case .inside:
            stateStr = "INSIDE"
            print("[BeaconRadar] Already inside region: \(region.identifier)")
            self.locationManager.startRangingBeacons(in: beaconRegion)
        case .outside:
            stateStr = "OUTSIDE"
            print("[BeaconRadar] Currently outside region: \(region.identifier)")
            self.locationManager.stopRangingBeacons(in: beaconRegion)
        case .unknown:
            stateStr = "UNKNOWN"
            print("[BeaconRadar] Unknown state for region: \(region.identifier)")
        }
        
        let regionData: [String: Any] = [
            "uuid": beaconRegion.uuid.uuidString,
            "identifier": beaconRegion.identifier,
            "major": beaconRegion.major?.intValue ?? NSNull(),
            "minor": beaconRegion.minor?.intValue ?? NSNull(),
            "state": stateStr
        ]
        
        self.sendEvent(name: "didDetermineStateForRegion", body: regionData)
    }
    
    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        
        print("[BeaconRadar] Did enter region: \(region.identifier)")
        
        let regionData: [String: Any] = [
            "uuid": beaconRegion.uuid.uuidString,
            "identifier": beaconRegion.identifier,
            "major": beaconRegion.major?.intValue ?? NSNull(),
            "minor": beaconRegion.minor?.intValue ?? NSNull()
        ]
        
        self.sendEvent(name: "didEnterRegion", body: regionData)
        self.locationManager.startRangingBeacons(in: beaconRegion)
    }
    
    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        
        print("[BeaconRadar] Did exit region: \(region.identifier)")
        
        let regionData: [String: Any] = [
            "uuid": beaconRegion.uuid.uuidString,
            "identifier": beaconRegion.identifier,
            "major": beaconRegion.major?.intValue ?? NSNull(),
            "minor": beaconRegion.minor?.intValue ?? NSNull()
        ]
        
        self.sendEvent(name: "didExitRegion", body: regionData)
        self.locationManager.stopRangingBeacons(in: beaconRegion)
    }
    
    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }

        if state == .inside {
            print("Already inside region, starting ranging...")
            locationManager.startRangingBeacons(in: beaconRegion)

            if let bridge = bridge {
                let regionData: [String: Any] = [
                    "uuid": beaconRegion.proximityUUID.uuidString,
                    "identifier": beaconRegion.identifier,
                    "major": beaconRegion.major?.stringValue ?? "",
                    "minor": beaconRegion.minor?.stringValue ?? ""
                ]

                bridge.eventDispatcher().sendAppEvent(withName: "didDetermineStateInside", body: regionData)
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didRangeBeacons beacons: [CLBeacon], in region: CLBeaconRegion) {
        guard !beacons.isEmpty else { return }
        
        let beaconArray = beacons.map { beacon -> [String: Any] in
            return [
                "uuid": beacon.uuid.uuidString,
                "major": beacon.major.intValue,
                "minor": beacon.minor.intValue,
                "distance": beacon.accuracy,
                "rssi": beacon.rssi,
                "proximity": beacon.proximity.rawValue
            ]
        }
        
        self.sendEvent(name: "onBeaconsDetected", body: beaconArray)
    }
    
    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        print("[BeaconRadar] Monitoring failed for region: \(region?.identifier ?? "unknown") - \(error.localizedDescription)")
        self.sendEvent(name: "onMonitoringFailed", body: ["error": error.localizedDescription])
    }
    
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("[BeaconRadar] Location manager failed: \(error.localizedDescription)")
        self.sendEvent(name: "onLocationManagerFailed", body: ["error": error.localizedDescription])
    }
}