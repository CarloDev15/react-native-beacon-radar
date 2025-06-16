import CoreLocation
import CoreBluetooth
import React

@objc(BeaconRadar)
class BeaconRadar: NSObject, RCTBridgeModule, CLLocationManagerDelegate, CBCentralManagerDelegate {
  
  static func moduleName() -> String {
    return "BeaconRadar"
  }

    private var locationManager: CLLocationManager!
    private var beaconRegion: CLBeaconRegion!
    public var bridge: RCTBridge!
    private var centralManager: CBCentralManager!

    @objc func startScanning(_ uuid: String, config: NSDictionary) {
      if #available(iOS 13.0, *) {
          DispatchQueue.main.async {
            self.locationManager = CLLocationManager()
            self.locationManager.delegate = self
            
            if let useBackgroundScanning = config["useBackgroundScanning"] as? Bool, useBackgroundScanning {
                self.locationManager.allowsBackgroundLocationUpdates = true
                self.locationManager.pausesLocationUpdatesAutomatically = false
            }
            
            let uuid = UUID(uuidString: uuid)!
            
            if let major = config["major"] as? String, let minor = config["minor"] as? String {
                self.beaconRegion = CLBeaconRegion(
                    proximityUUID: uuid,
                    major: CLBeaconMajorValue(major) ?? 0,
                    minor: CLBeaconMinorValue(minor) ?? 0,
                    identifier: "RNIbeaconScannerRegion"
                )
            } else {
                self.beaconRegion = CLBeaconRegion(proximityUUID: uuid, identifier: "RNIbeaconScannerRegion")
            }
            
            self.beaconRegion.notifyOnEntry = true
            self.beaconRegion.notifyOnExit = true
            self.beaconRegion.notifyEntryStateOnDisplay = true
            
            let authStatus = CLLocationManager.authorizationStatus()
            if authStatus == .notDetermined {
                self.locationManager.requestAlwaysAuthorization()
            } else if authStatus == .authorizedAlways || authStatus == .authorizedWhenInUse {
                self.startMonitoringAndRanging()
            }
        }
      } else {
        //TODO Handling older versions
      }
  }
    
    @objc func stopScanning() {
        if let beaconRegion = self.beaconRegion {
            self.locationManager.stopMonitoring(for: beaconRegion)
            self.locationManager.stopRangingBeacons(in: beaconRegion)
            self.beaconRegion = nil
            self.locationManager = nil
        }
    }
    
    @objc func initializeBluetoothManager() {
        centralManager = CBCentralManager(delegate: self, queue: nil, options: [CBCentralManagerOptionShowPowerAlertKey: false])
    }
    
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        var msg = ""

        switch central.state {
        case .unknown:
            msg = "unknown"
        case .resetting:
            msg = "resetting"
        case .unsupported:
            msg = "unsupported"
        case .unauthorized:
            msg = "unauthorized"
        case .poweredOff:
            msg = "poweredOff"
        case .poweredOn:
            msg = "poweredOn"
        @unknown default:
            msg = "unknown"
        }
        bridge.eventDispatcher().sendAppEvent(withName: "onBluetoothStateChanged", body: ["state": msg])
    }

    @objc func requestAlwaysAuthorization(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        let locationManager = CLLocationManager()
        locationManager.delegate = self
        locationManager.requestAlwaysAuthorization()
        let status = CLLocationManager.authorizationStatus()
        let statusString = statusToString(status)
        resolve(["status": statusString])
    }
    
    @objc func requestWhenInUseAuthorization(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        let locationManager = CLLocationManager()
        locationManager.delegate = self
        locationManager.requestWhenInUseAuthorization()
        let status = CLLocationManager.authorizationStatus()
        let statusString = statusToString(status)
        resolve(["status": statusString])
    }

    @objc func getAuthorizationStatus(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        let status = CLLocationManager.authorizationStatus()
        resolve(statusToString(status))
    }

    // MARK: - CLLocationManagerDelegate methods

    func locationManager(_ manager: CLLocationManager, didRangeBeacons beacons: [CLBeacon], in region: CLBeaconRegion) {
        let beaconArray = beacons.map { beacon -> [String: Any] in
            if #available(iOS 13.0, *) {
                return [
                    "uuid": beacon.uuid.uuidString,
                    "major": beacon.major.stringValue,
                    "minor": beacon.minor.stringValue,
                    "distance": beacon.accuracy,
                    "rssi": beacon.rssi,
                    "txPower": 0,
                    "bluetoothName": "",
                    "bluetoothAddress": "",
                    "manufacturer": 0,
                    "timestamp": Date().timeIntervalSince1970 * 1000
                ]
            } else {
                return [:]
            }
        }

        let eventData: [String: Any] = [
            "beacons": beaconArray,
            "uuid": region.proximityUUID.uuidString,
            "identifier": region.identifier
        ]

        if let bridge = bridge {
            bridge.eventDispatcher().sendAppEvent(withName: "onBeaconsDetected", body: eventData)
        }
    }
  
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if #available(iOS 14.0, *) {
            if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse {
                startMonitoringAndRanging()
            }
        } else {
            if CLLocationManager.authorizationStatus() == .authorizedAlways || CLLocationManager.authorizationStatus() == .authorizedWhenInUse {
                startMonitoringAndRanging()
            }
        }
    }
    
    func locationManagerDidPauseLocationUpdates(_ manager: CLLocationManager) {
        if let beaconRegion = self.beaconRegion {
            locationManager.startRangingBeacons(in: beaconRegion)
        }
    }

    func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        if let beaconRegion = self.beaconRegion {
            locationManager.startRangingBeacons(in: beaconRegion)
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        if let beaconRegion = region as? CLBeaconRegion {
            let regionData: [String: Any] = [
                "uuid": beaconRegion.proximityUUID.uuidString,
                "identifier": beaconRegion.identifier,
                "major": beaconRegion.major?.stringValue ?? "",
                "minor": beaconRegion.minor?.stringValue ?? ""
            ]
            
            if let bridge = bridge {
                bridge.eventDispatcher().sendAppEvent(withName: "didEnterRegion", body: regionData)
            }
            // Start ranging when entering the region
            locationManager.startRangingBeacons(in: beaconRegion)
        }
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        if let beaconRegion = region as? CLBeaconRegion {
            let regionData: [String: Any] = [
                "uuid": beaconRegion.proximityUUID.uuidString,
                "identifier": beaconRegion.identifier,
                "major": beaconRegion.major?.stringValue ?? "",
                "minor": beaconRegion.minor?.stringValue ?? ""
            ]
            
            if let bridge = bridge {
                bridge.eventDispatcher().sendAppEvent(withName: "didExitRegion", body: regionData)
            }
            // Stop ranging when exiting the region
            locationManager.stopRangingBeacons(in: beaconRegion)
        }
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        print("Monitoring failed for region: \(region?.identifier ?? "Unknown") with error: \(error.localizedDescription)")
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("Location manager failed with error: \(error.localizedDescription)")
    }
    
    private func statusToString(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined:
            return "notDetermined"
        case .restricted:
            return "restricted"
        case .denied:
            return "denied"
        case .authorizedAlways:
            return "authorizedAlways"
        case .authorizedWhenInUse:
            return "authorizedWhenInUse"
        @unknown default:
            return "unknown"
        }
    }

    private func startMonitoringAndRanging() {
        guard let beaconRegion = self.beaconRegion else { return }
        
        self.locationManager.startMonitoring(for: beaconRegion)
        // Check if we are already in the region
        self.locationManager.requestState(for: beaconRegion)
    }
}