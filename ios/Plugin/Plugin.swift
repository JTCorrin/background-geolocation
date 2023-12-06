import Capacitor
import Foundation
import UIKit
import CoreLocation
import FirebaseCore
import FirebaseFirestore




// Avoids a bewildering type warning.
let null = Optional<Double>.none as Any
struct w3wApiResponse: Codable {
    let country: String?
    let square: Square?
    let nearestPlace: String?
    let coordinates: Coordinates?
    let words: String?
    let language: String?
    let locale: String?
    let map: String?

    struct Square: Codable {
        let southwest: Coordinates?
        let northeast: Coordinates?
    }

    struct Coordinates: Codable {
        let lng: Double?
        let lat: Double?
    }
}

func formatLocation(_ location: CLLocation) -> PluginCallResultData {
    var simulated = false;
    if #available(iOS 15, *) {
        // Prior to iOS 15, it was not possible to detect simulated locations.
        // But in general, it is very difficult to simulate locations on iOS in
        // production.
        if location.sourceInformation != nil {
            simulated = location.sourceInformation!.isSimulatedBySoftware;
        }
    }
    return [
        "latitude": location.coordinate.latitude,
        "longitude": location.coordinate.longitude,
        "accuracy": location.horizontalAccuracy,
        "altitude": location.altitude,
        "altitudeAccuracy": location.verticalAccuracy,
        "simulated": simulated,
        "speed": location.speed < 0 ? null : location.speed,
        "bearing": location.course < 0 ? null : location.course,
        "time": NSNumber(
            value: Int(
                location.timestamp.timeIntervalSince1970 * 1000
            )
        ),
    ]
}

class Watcher {
    let callbackId: String
    let locationManager: CLLocationManager = CLLocationManager()
    private let created = Date()
    private let allowStale: Bool
    private var isUpdatingLocation: Bool = false
    init(_ id: String, stale: Bool) {
        callbackId = id
        allowStale = stale
    }
    func start() {
        // Avoid unnecessary calls to startUpdatingLocation, which can
        // result in extraneous invocations of didFailWithError.
        if !isUpdatingLocation {
            locationManager.startUpdatingLocation()
            isUpdatingLocation = true
        }
    }
    func stop() {
        if isUpdatingLocation {
            locationManager.stopUpdatingLocation()
            isUpdatingLocation = false
        }
    }
    func isLocationValid(_ location: CLLocation) -> Bool {
        return (
            allowStale ||
            location.timestamp >= created
        )
    }
}

@objc(BackgroundGeolocation)
public class BackgroundGeolocation : CAPPlugin, CLLocationManagerDelegate {

    private var db = Firestore.firestore()
    private var watchers = [Watcher]()
    private var sessionId = ""
    private var w3wAPIKey = ""

    @objc public override func load() {
        if (FirebaseApp.app() == nil) {
            FirebaseApp.configure();
        }
        UIDevice.current.isBatteryMonitoringEnabled = true
    }

    @objc func addWatcher(_ call: CAPPluginCall) {
        call.keepAlive = true
        sessionId = call.getString("sessionId", "")
        w3wAPIKey = call.getString("w3wAPIKey", "")
        // CLLocationManager requires main thread
        DispatchQueue.main.async {
            let background = call.getString("backgroundMessage") != nil
            let watcher = Watcher(
                call.callbackId,
                stale: call.getBool("stale") ?? false
            )
            let manager = watcher.locationManager
            manager.delegate = self
            let externalPower = [
                .full,
                .charging
            ].contains(UIDevice.current.batteryState)
            manager.desiredAccuracy = (
                externalPower
                ? kCLLocationAccuracyBestForNavigation
                : kCLLocationAccuracyBest
            )
            var distanceFilter = call.getDouble("distanceFilter")
            // It appears that setting manager.distanceFilter to 0 can prevent
            // subsequent location updates. See issue #88.
            if distanceFilter == nil || distanceFilter == 0 {
                distanceFilter = kCLDistanceFilterNone
            }
            manager.distanceFilter = distanceFilter!
            manager.allowsBackgroundLocationUpdates = background
            manager.showsBackgroundLocationIndicator = background
            manager.pausesLocationUpdatesAutomatically = false
            self.watchers.append(watcher)
            if call.getBool("requestPermissions") != false {
                let status = CLLocationManager.authorizationStatus()
                if [
                    .notDetermined,
                    .denied,
                    .restricted,
                ].contains(status) {
                    return (
                        background
                        ? manager.requestAlwaysAuthorization()
                        : manager.requestWhenInUseAuthorization()
                    )
                }
                if (
                    background && status == .authorizedWhenInUse
                ) {
                    // Attempt to escalate.
                    manager.requestAlwaysAuthorization()
                }
            }
            return watcher.start()
        }
    }

    @objc func removeWatcher(_ call: CAPPluginCall) {
        // CLLocationManager requires main thread
        DispatchQueue.main.async {
            if let callbackId = call.getString("id") {
                if let index = self.watchers.firstIndex(
                    where: { $0.callbackId == callbackId }
                ) {
                    self.watchers[index].locationManager.stopUpdatingLocation()
                    self.watchers.remove(at: index)
                }
                if let savedCall = self.bridge?.savedCall(withID: callbackId) {
                    self.bridge?.releaseCall(savedCall)
                }
                return call.resolve()
            }
            return call.reject("No callback ID")
        }
    }

    @objc func openSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard let settingsUrl = URL(
                string: UIApplication.openSettingsURLString
            ) else {
                return call.reject("No link to settings available")
            }

            if UIApplication.shared.canOpenURL(settingsUrl) {
                UIApplication.shared.open(settingsUrl, completionHandler: {
                    (success) in
                    if (success) {
                        return call.resolve()
                    } else {
                        return call.reject("Failed to open settings")
                    }
                })
            } else {
                return call.reject("Cannot open settings")
            }
        }
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didFailWithError error: Error
    ) {
        if let watcher = self.watchers.first(
            where: { $0.locationManager == manager }
        ) {
            if let call = self.bridge?.savedCall(withID: watcher.callbackId) {
                if let clErr = error as? CLError {
                    if clErr.code == .locationUnknown {
                        // This error is sometimes sent by the manager if
                        // it cannot get a fix immediately.
                        return
                    } else if (clErr.code == .denied) {
                        watcher.stop()
                        return call.reject(
                            "Permission denied.",
                            "NOT_AUTHORIZED"
                        )
                    }
                }
                return call.reject(error.localizedDescription, nil, error)
            }
        }
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        if let location = locations.last {
            if let watcher = self.watchers.first(
                where: { $0.locationManager == manager }
            ) {
                if watcher.isLocationValid(location) {
                    // TODO Update firestore directly
                    if let call = self.bridge?.savedCall(withID: watcher.callbackId) {
                        getW3Words(location: location) { words in
                            self.updateLocationsArray(sessionId: self.sessionId, location: location, w3w: words)
                        }
                        return call.resolve(formatLocation(location))
                    }
                }
            }
        }
    }

    public func locationManager(
        _ manager: CLLocationManager,
        didChangeAuthorization status: CLAuthorizationStatus
    ) {
        // If this method is called before the user decides on a permission, as
        // it is on iOS 14 when the permissions dialog is presented, we ignore
        // it.
        if status != .notDetermined {
            if let watcher = self.watchers.first(
                where: { $0.locationManager == manager }
            ) {
                return watcher.start()
            }
        }
    }

    private func getW3Words(location: CLLocation, completion: @escaping (String?) -> Void) {
        let url = URL(string: "https://api.what3words.com/v3/convert-to-3wa?coordinates=\(location.coordinate.latitude)%2C\(location.coordinate.latitude)&key=\(w3wAPIKey)")!
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            guard let data = data, error == nil else {
                print("Error during HTTP request: \(error?.localizedDescription ?? "Unknown error")")
                completion(nil)
                return
            }

            if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 {
                do {
                    let response = try JSONDecoder().decode(w3wApiResponse.self, from: data)
                    if let words = response.words {
                        completion(words) // Call the completion handler with the words
                    } else {
                        print("Words not found in the response")
                        completion(nil)
                    }
                } catch {
                    print("Error parsing JSON: \(error)")
                    completion(nil)
                }
            } else {
                print("Error: HTTP status code not 200")
                completion(nil)
            }
        }
        task.resume()
    }

    private func updateLocationsArray(sessionId: String, location: CLLocation, w3w: String?) {
        // Get a reference to the document you want to update
        let docRef = db.collection("sessions").document(sessionId)
        let w3wValue = w3w ?? "Unable to ascertain"
        
        // Create a new location dictionary with the current timestamp
        let newLocation: [String: Any] = [
            "type": "tracked",
            "timestamp": NSDate().timeIntervalSince1970 * 1000,
            "geopoint": GeoPoint(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude),
            "address": "Not available when tracking",
            "w3w": w3wValue
        ]

        let newLog: [String: Any] = [
            "timestamp": NSDate().timeIntervalSince1970 * 1000,
            "createdBy": "Guardian",
            "text": "Latest location received: " + "\(location.coordinate.latitude)" + ":" + "\(location.coordinate.latitude). ///what3words: " + "\(w3wValue)"
        ]
        
        // Add the new location to the "locations" array
        docRef.updateData([
                "locations": FieldValue.arrayUnion([newLocation]),
                "logs": FieldValue.arrayUnion([newLog])
            ]) { error in
            if let error = error {
                print("Error updating document: \(error)")
            } else {
                print("Document successfully updated!")
            }
        }
    }
}

