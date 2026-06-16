import * as Location from 'expo-location';
import { Platform } from 'react-native';

/**
 * Location service for GPS access on mobile app.
 * Handles location permissions and coordinate requests.
 */

export const locationService = {
  /**
   * Request location permission from user.
   * Returns true if permission granted, false otherwise.
   */
  async requestPermission() {
    try {
      const { status } = await Location.requestForegroundPermissionsAsync();
      return status === 'granted';
    } catch (error) {
      console.error('Error requesting location permission:', error);
      return false;
    }
  },

  /**
   * Check if location permission is already granted.
   */
  async checkPermission() {
    try {
      const { status } = await Location.getForegroundPermissionsAsync();
      return status === 'granted';
    } catch (error) {
      console.error('Error checking location permission:', error);
      return false;
    }
  },

  /**
   * Get user's current GPS coordinates.
   * Returns { latitude, longitude, accuracy } or null if failed/unavailable.
   *
   * On web (Expo): Returns mock location or requires browser location permission.
   * On Android/iOS: Uses native location services.
   */
  async getCurrentLocation() {
    try {
      // Check permission first
      const hasPermission = await this.checkPermission();
      if (!hasPermission) {
        console.warn('Location permission not granted');
        return null;
      }

      // Request single location update (not continuous tracking)
      const location = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
        timeout: 5000, // 5 second timeout
      });

      return {
        latitude: location.coords.latitude,
        longitude: location.coords.longitude,
        accuracy: location.coords.accuracy,
      };
    } catch (error) {
      console.error('Error getting current location:', error);
      return null;
    }
  },

  /**
   * Reverse geocode coordinates to get address info.
   * Returns address object or null if failed.
   */
  async reverseGeocode(latitude, longitude) {
    try {
      const addresses = await Location.reverseGeocodeAsync({ latitude, longitude });
      return addresses && addresses.length > 0 ? addresses[0] : null;
    } catch (error) {
      console.error('Error reverse geocoding:', error);
      return null;
    }
  },

  /**
   * Check if location services are enabled on device.
   */
  async isLocationEnabled() {
    try {
      if (Platform.OS === 'web') {
        // Assume location might be available on web via browser
        return true;
      }
      return await Location.hasServicesEnabledAsync();
    } catch (error) {
      console.error('Error checking location services:', error);
      return false;
    }
  },

  /**
   * Calculate distance between two points using Haversine formula (km).
   */
  calculateDistance(lat1, lon1, lat2, lon2) {
    const R = 6371; // Earth's radius in km
    const dLat = this.toRad(lat2 - lat1);
    const dLon = this.toRad(lon2 - lon1);
    const a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(this.toRad(lat1)) * Math.cos(this.toRad(lat2)) *
      Math.sin(dLon / 2) * Math.sin(dLon / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return R * c;
  },

  toRad(degrees) {
    return degrees * (Math.PI / 180);
  },
};
