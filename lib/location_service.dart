import 'package:location/location.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:adhan/adhan.dart';

class LocationService {
  static Future<Coordinates?> getSavedCoordinates() async {
    final prefs = await SharedPreferences.getInstance();
    double? lat = prefs.getDouble('latitude');
    double? lng = prefs.getDouble('longitude');

    if (lng != null) {
      return Coordinates(lat!, lng);
    }
    return null;
  }

  static Future<Coordinates?> requestAndSaveLocation() async {
    Location location = Location();

    // Check if location services are enabled
    bool serviceEnabled = await location.serviceEnabled();
    
    // If location service is not enabled, request the user to enable it
    if (!serviceEnabled) {
      serviceEnabled = await location.requestService();
      if (!serviceEnabled) {
        // If the user still doesn't enable location service, return null
        return null;
      }
    }

    // Request permission if not already granted
    PermissionStatus permissionGranted = await location.hasPermission();
    if (permissionGranted == PermissionStatus.denied) {
      permissionGranted = await location.requestPermission();
      if (permissionGranted != PermissionStatus.granted) {
        // If permission is denied, return null
        return null;
      }
    }

    // Fetch location data after service is enabled and permission is granted
    final locationData = await location.getLocation();
    final prefs = await SharedPreferences.getInstance();
    prefs.setDouble('latitude', locationData.latitude!);
    prefs.setDouble('longitude', locationData.longitude!);

    return Coordinates(locationData.latitude!, locationData.longitude!);
  }
}
