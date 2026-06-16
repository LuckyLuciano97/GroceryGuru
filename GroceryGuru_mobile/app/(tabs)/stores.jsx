import { useEffect, useState } from 'react';
import { View, Text, TouchableOpacity, FlatList, ActivityIndicator, Alert, StyleSheet, ScrollView, Platform } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { locationService } from '../../services/location';
import { storeService } from '../../services/api';

// Conditionally import react-native-maps (not available on web)
let MapView = null;
let Marker = null;
if (Platform.OS !== 'web') {
  try {
    const maps = require('react-native-maps');
    MapView = maps.default;
    Marker = maps.Marker;
  } catch (e) {
    console.warn('react-native-maps not available:', e.message);
  }
}

export default function StoresScreen() {
  const [location, setLocation] = useState(null);
  const [stores, setStores] = useState([]);
  const [loading, setLoading] = useState(false);
  const [permissionGranted, setPermissionGranted] = useState(false);
  const [radius, setRadius] = useState(25);
  const [viewMode, setViewMode] = useState('list'); // 'list' or 'map'
  const [error, setError] = useState(null);

  // Request location permission on mount
  useEffect(() => {
    requestLocationPermission();
  }, []);

  const requestLocationPermission = async () => {
    const hasPermission = await locationService.requestPermission();
    setPermissionGranted(hasPermission);
    if (hasPermission) {
      fetchCurrentLocation();
    } else {
      Alert.alert('Permission Denied', 'Location permission is required to find nearby stores.');
    }
  };

  const fetchCurrentLocation = async () => {
    setLoading(true);
    setError(null);
    try {
      const coords = await locationService.getCurrentLocation();
      if (coords) {
        setLocation(coords);
        fetchNearbyStores(coords);
      } else {
        setError('Could not get your location. Please try again.');
        Alert.alert('Location Error', 'Could not get your location. Please ensure location services are enabled.');
      }
    } catch (err) {
      setError(err.message);
      Alert.alert('Error', 'Failed to get location: ' + err.message);
    } finally {
      setLoading(false);
    }
  };

  const fetchNearbyStores = async (coords) => {
    try {
      const response = await storeService.getNearbyStores(coords.latitude, coords.longitude, radius);
      setStores(response.data);
    } catch (err) {
      console.error('Error fetching stores:', err);
      setError('Failed to load nearby stores');
      Alert.alert('Error', 'Failed to load nearby stores: ' + err.message);
    }
  };

  const handleRadiusChange = (increment) => {
    const newRadius = radius + increment;
    if (newRadius >= 5 && newRadius <= 50) {
      setRadius(newRadius);
    }
  };

  const handleRefresh = () => {
    if (location) {
      setLoading(true);
      fetchNearbyStores(location);
      setLoading(false);
    }
  };

  const renderMapView = () => {
    if (!MapView) {
      return (
        <View style={styles.centerContainer}>
          <Ionicons name="map" size={48} color="#999" />
          <Text style={styles.emptyText}>Map view is not available on this platform</Text>
          <TouchableOpacity style={styles.retryBtn} onPress={() => setViewMode('list')}>
            <Text style={styles.retryBtnText}>Switch to List</Text>
          </TouchableOpacity>
        </View>
      );
    }

    if (!location) {
      return (
        <View style={styles.centerContainer}>
          <ActivityIndicator size="large" color="#5B4FE8" />
          <Text style={styles.loadingText}>Getting your location...</Text>
        </View>
      );
    }

    return (
      <MapView
        style={styles.map}
        initialRegion={{
          latitude: location.latitude,
          longitude: location.longitude,
          latitudeDelta: radius * 0.02,
          longitudeDelta: radius * 0.02,
        }}
        showsUserLocation={true}
        showsMyLocationButton={true}
      >
        {stores.map((store) => (
          store.latitude && store.longitude && (
            <Marker
              key={store.id}
              coordinate={{ latitude: store.latitude, longitude: store.longitude }}
              title={store.name}
              description={store.minPrice ? `From €${store.minPrice.toFixed(2)}` : store.chainName}
            />
          )
        ))}
      </MapView>
    );
  };

  const renderListView = () => {
    if (loading) {
      return (
        <View style={styles.centerContainer}>
          <ActivityIndicator size="large" color="#5B4FE8" />
          <Text style={styles.loadingText}>Finding nearby stores...</Text>
        </View>
      );
    }

    if (error && stores.length === 0) {
      return (
        <View style={styles.centerContainer}>
          <Ionicons name="alert-circle" size={48} color="#dc3545" />
          <Text style={styles.errorText}>{error}</Text>
          <TouchableOpacity style={styles.retryBtn} onPress={fetchCurrentLocation}>
            <Text style={styles.retryBtnText}>Retry</Text>
          </TouchableOpacity>
        </View>
      );
    }

    if (stores.length === 0) {
      return (
        <View style={styles.centerContainer}>
          <Ionicons name="map" size={48} color="#999" />
          <Text style={styles.emptyText}>No stores found within {radius} km</Text>
          <Text style={styles.emptySubtext}>Try increasing the search radius</Text>
        </View>
      );
    }

    return (
      <FlatList
        data={stores}
        keyExtractor={(item) => `${item.id}`}
        renderItem={({ item }) => <StoreListItem store={item} />}
        contentContainerStyle={styles.listContent}
        scrollEnabled={false}
      />
    );
  };


  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>Nearby Stores</Text>
        {MapView && (
          <TouchableOpacity
            style={styles.toggleBtn}
            onPress={() => setViewMode(viewMode === 'list' ? 'map' : 'list')}
          >
            <Ionicons name={viewMode === 'list' ? 'map' : 'list'} size={20} color="#fff" />
          </TouchableOpacity>
        )}
      </View>

      {/* Location & Radius Controls */}
      <View style={styles.controlsSection}>
        <TouchableOpacity style={styles.locationBtn} onPress={fetchCurrentLocation} disabled={loading}>
          <Ionicons name="locate" size={18} color="#fff" />
          <Text style={styles.locationBtnText}>Find Nearby</Text>
        </TouchableOpacity>

        <View style={styles.radiusControl}>
          <TouchableOpacity
            style={styles.radiusBtn}
            onPress={() => handleRadiusChange(-5)}
            disabled={radius <= 5 || loading}
          >
            <Text style={styles.radiusBtnText}>−</Text>
          </TouchableOpacity>
          <View style={styles.radiusDisplay}>
            <Text style={styles.radiusValue}>{radius}</Text>
            <Text style={styles.radiusUnit}>km</Text>
          </View>
          <TouchableOpacity
            style={styles.radiusBtn}
            onPress={() => handleRadiusChange(5)}
            disabled={radius >= 50 || loading}
          >
            <Text style={styles.radiusBtnText}>+</Text>
          </TouchableOpacity>
        </View>

        <TouchableOpacity style={styles.refreshBtn} onPress={handleRefresh} disabled={loading || !location}>
          <Ionicons name="refresh" size={18} color="#fff" />
        </TouchableOpacity>
      </View>

      {/* Main content - toggleable */}
      {viewMode === 'map' ? (
        <View style={styles.mapContainer}>
          {renderMapView()}
        </View>
      ) : (
        <ScrollView style={styles.listContainer} contentContainerStyle={styles.scrollContent}>
          {renderListView()}
        </ScrollView>
      )}

      {/* Store count */}
      {stores.length > 0 && (
        <View style={styles.footer}>
          <Text style={styles.footerText}>
            {stores.length} store{stores.length !== 1 ? 's' : ''} within {radius} km
          </Text>
        </View>
      )}
    </View>
  );
}

// List item component
function StoreListItem({ store }) {
  return (
    <View style={styles.storeCard}>
      <View style={styles.storeInfo}>
        <View style={styles.storeNameRow}>
          <Text style={styles.storeName} numberOfLines={1}>
            {store.name}
          </Text>
          <Text style={styles.distance}>{store.distanceKm.toFixed(1)} km</Text>
        </View>
        <View style={styles.storeDetails}>
          <Text style={styles.chainName}>{store.chainName || 'Unknown Chain'}</Text>
          {store.opensAt && store.closesAt && (
            <Text style={styles.hours}>
              {store.opensAt} - {store.closesAt}
            </Text>
          )}
        </View>
        {store.minPrice && (
          <Text style={styles.minPrice}>From €{store.minPrice.toFixed(2)}</Text>
        )}
      </View>
      <TouchableOpacity style={styles.directionBtn}>
        <Ionicons name="navigate" size={22} color="#5B4FE8" />
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },

  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 12,
    backgroundColor: '#1E2A78',
  },
  title: { fontSize: 20, fontWeight: '700', color: '#fff' },
  toggleBtn: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: 'rgba(255,255,255,0.2)',
    alignItems: 'center',
    justifyContent: 'center',
  },

  controlsSection: {
    flexDirection: 'row',
    gap: 10,
    padding: 12,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
  },
  locationBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingVertical: 10,
    backgroundColor: '#5B4FE8',
    borderRadius: 8,
  },
  locationBtnText: { color: '#fff', fontWeight: '700', fontSize: 13 },

  radiusControl: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    overflow: 'hidden',
  },
  radiusBtn: { width: 32, height: 36, alignItems: 'center', justifyContent: 'center', backgroundColor: '#f9f9f9' },
  radiusBtnText: { fontSize: 18, fontWeight: '700', color: '#333' },
  radiusDisplay: { paddingHorizontal: 10, alignItems: 'center' },
  radiusValue: { fontSize: 16, fontWeight: '700', color: '#333' },
  radiusUnit: { fontSize: 11, color: '#888' },

  refreshBtn: {
    width: 36,
    height: 36,
    borderRadius: 8,
    backgroundColor: '#5B4FE8',
    alignItems: 'center',
    justifyContent: 'center',
  },

  mapContainer: { flex: 1 },
  map: { flex: 1 },
  listContainer: { flex: 1 },
  scrollContent: { paddingHorizontal: 12, paddingVertical: 12 },

  centerContainer: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 20 },
  loadingText: { marginTop: 12, fontSize: 14, color: '#666' },
  errorText: { marginTop: 12, fontSize: 14, color: '#dc3545', textAlign: 'center' },
  emptyText: { marginTop: 12, fontSize: 14, fontWeight: '600', color: '#333', textAlign: 'center' },
  emptySubtext: { marginTop: 4, fontSize: 12, color: '#888', textAlign: 'center' },
  retryBtn: { marginTop: 16, paddingHorizontal: 20, paddingVertical: 10, backgroundColor: '#dc3545', borderRadius: 8 },
  retryBtnText: { color: '#fff', fontWeight: '700' },

  listContent: { paddingBottom: 20 },

  storeCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    backgroundColor: '#fff',
    padding: 12,
    borderRadius: 10,
    marginBottom: 8,
    borderWidth: 1,
    borderColor: '#eee',
  },
  storeInfo: { flex: 1 },
  storeNameRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 },
  storeName: { fontSize: 14, fontWeight: '600', color: '#333', flex: 1 },
  distance: { fontSize: 13, fontWeight: '700', color: '#5B4FE8', marginLeft: 8 },

  storeDetails: { marginVertical: 4 },
  chainName: { fontSize: 12, color: '#666' },
  hours: { fontSize: 11, color: '#888', marginTop: 2 },
  minPrice: { fontSize: 12, fontWeight: '700', color: '#1f9d55', marginTop: 6 },

  directionBtn: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },

  footer: { paddingHorizontal: 14, paddingVertical: 10, backgroundColor: '#f0f0f0', borderTopWidth: 1, borderTopColor: '#ddd' },
  footerText: { fontSize: 12, color: '#666', textAlign: 'center' },
});
