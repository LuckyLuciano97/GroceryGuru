import { useState, useEffect, useRef } from 'react';
import { View, Text, TextInput, TouchableOpacity, FlatList, StyleSheet, Alert, ScrollView, ActivityIndicator, Platform, Image, Linking } from 'react-native';
import { useLocalSearchParams, useRouter, Stack } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { shoppingListService, productService, storeService } from '../../services/api';
import { useAuth } from '../../services/AuthContext';
import { getCategoryIcon } from '../../services/categories';
import { getChainLogo, getChainColor, getChainInitial, getChainUrl } from '../../services/chains';
import { colors } from '../../services/theme';
import { locationService } from '../../services/location';
import { useI18n } from '../../services/i18n';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

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

// Chain logo, with a colored initial-avatar fallback for chains we lack a logo for.
function ChainLogo({ chainName, size = 40 }) {
  const logo = getChainLogo(chainName);
  if (logo) {
    return (
      <Image
        source={logo}
        style={{ width: size, height: size, borderRadius: size / 5, backgroundColor: '#fff' }}
        resizeMode="contain"
      />
    );
  }
  return (
    <View style={{
      width: size, height: size, borderRadius: size / 5,
      backgroundColor: getChainColor(chainName),
      alignItems: 'center', justifyContent: 'center',
    }}>
      <Text style={{ color: '#fff', fontWeight: '800', fontSize: size * 0.45 }}>
        {getChainInitial(chainName)}
      </Text>
    </View>
  );
}

export default function ListDetail() {
  const { id } = useLocalSearchParams();
  const { user } = useAuth();
  const router = useRouter();
  const { t } = useI18n();

  const [list, setList] = useState(null);
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);

  // Add item
  const [searchQuery, setSearchQuery] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [addOpen, setAddOpen] = useState(false);
  const debounceRef = useRef(null);

  // Optimize
  const [results, setResults] = useState(null);
  const [optimizing, setOptimizing] = useState(false);

  // Store checklist
  const [selectedStore, setSelectedStore] = useState(null);
  const [checkedShop, setCheckedShop] = useState({});
  const [checkedItems, setCheckedItems] = useState({});

  // Swap
  const [swapIndex, setSwapIndex] = useState(null);
  const [alternatives, setAlternatives] = useState([]);
  const [loadingAlts, setLoadingAlts] = useState(false);

  // Nearby stores (shown after optimize)
  const [nearbyStores, setNearbyStores] = useState([]);
  const [storeViewMode, setStoreViewMode] = useState('list'); // 'list' or 'map'

  // Sharing
  const [shareOpen, setShareOpen] = useState(false);
  const [shareEmail, setShareEmail] = useState('');
  const [shareError, setShareError] = useState('');
  const [shareSuccess, setShareSuccess] = useState('');
  const [members, setMembers] = useState([]);
  const [loadingMembers, setLoadingMembers] = useState(false);

  const fetchList = async () => {
    try {
      const res = await shoppingListService.getForUser(user.id);
      const found = res.data.find((l) => l.id === parseInt(id));
      if (found) { setList(found); setItems(found.items || []); }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchList(); }, [id]);

  // WebSocket — live sync
  useEffect(() => {
    const wsUrl = Platform.select({
      web: 'http://localhost:8080/ws',
      default: 'https://ditch-cane-pamperer.ngrok-free.dev/ws',
    });
    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: 3000,
      onConnect: () => {
        client.subscribe(`/topic/list/${id}`, (message) => {
          const msg = JSON.parse(message.body);
          if (['ITEM_ADDED', 'ITEM_REMOVED', 'ITEM_UPDATED', 'MEMBER_ADDED', 'MEMBER_REMOVED'].includes(msg.action)) {
            fetchList();
          }
        });
      },
    });
    client.activate();
    return () => { client.deactivate(); };
  }, [id]);

  const fetchMembers = async () => {
    setLoadingMembers(true);
    try {
      const res = await shoppingListService.getMembers(id);
      setMembers(res.data);
    } catch { /* ignore */ }
    finally { setLoadingMembers(false); }
  };

  const handleShare = async () => {
    if (!shareEmail.trim()) return;
    setShareError(''); setShareSuccess('');
    try {
      await shoppingListService.shareList(id, shareEmail.trim(), user.id);
      setShareEmail('');
      setShareOpen(false);
      Alert.alert(t('success'), t('sharedSuccess'));
    } catch (err) {
      setShareError(err.response?.data?.error || 'Failed to share list');
    }
  };

  const handleRemoveMember = async (memberId) => {
    try {
      await shoppingListService.removeMember(id, memberId, user.id);
      fetchMembers();
    } catch { Alert.alert(t('error'), t('failedToRemove')); }
  };

  const isOwner = list?.user?.id === user?.id;

  // ─── Add item logic ───
  const handleSearchChange = (text) => {
    setSearchQuery(text);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!text.trim()) { setSearchResults([]); return; }
    debounceRef.current = setTimeout(async () => {
      try {
        const res = await productService.search(text, 0, 6);
        setSearchResults(res.data.content || res.data);
      } catch { setSearchResults([]); }
    }, 400);
  };

  const addGeneric = async () => {
    if (!searchQuery.trim()) return;
    try {
      await shoppingListService.addGenericItem(id, searchQuery.trim(), 1);
      setSearchQuery(''); setSearchResults([]); setResults(null);
      fetchList();
    } catch (err) { Alert.alert(t('error'), t('failedToAdd')); }
  };

  const addSpecific = async (productId) => {
    try {
      await shoppingListService.addItem(id, productId, 1);
      setSearchQuery(''); setSearchResults([]); setResults(null);
      fetchList();
    } catch (err) { Alert.alert(t('error'), t('failedToAdd')); }
  };

  const removeItem = async (itemId) => {
    try {
      await shoppingListService.removeItem(itemId);
      setResults(null);
      fetchList();
    } catch (err) { Alert.alert(t('error'), t('failedToRemove')); }
  };

  const changeQty = async (itemId, newQty) => {
    if (newQty < 1) return;
    try {
      await shoppingListService.updateItemQuantity(itemId, newQty);
      setItems((prev) => prev.map((i) => (i.id === itemId ? { ...i, quantity: newQty } : i)));
      setResults(null);
    } catch (err) { console.error(err); }
  };

  // ─── Optimize ───
  const handleOptimize = async () => {
    setOptimizing(true);
    setSelectedStore(null);
    setCheckedShop({});
    setNearbyStores([]);
    try {
      const res = await shoppingListService.optimize(id);
      setResults(res.data);

      // Also fetch nearby stores and merge with chain totals
      try {
        const hasPermission = await locationService.requestPermission();
        if (hasPermission) {
          const coords = await locationService.getCurrentLocation();
          if (coords) {
            const storesRes = await storeService.getNearbyStores(coords.latitude, coords.longitude, 50);
            const chainTotals = {};
            for (const r of res.data) {
              chainTotals[r.chainName.toUpperCase()] = {
                totalPrice: r.totalPrice,
                complete: r.complete,
                missingProducts: r.missingProducts,
                items: r.items,
              };
            }
            // Show ALL nearby stores, sorted by distance (already sorted by backend)
            // Deduplicate stores with same name + chain — keep only the closest one
            const seen = new Set();
            const deduped = storesRes.data.filter(s => {
              const key = `${(s.chainName || '').toUpperCase()}::${(s.name || '').toUpperCase()}`;
              if (seen.has(key)) return false;
              seen.add(key);
              return true;
            });
            // Attach cart info if their chain is in the optimization results
            const merged = deduped.map(s => {
              const chainInfo = chainTotals[s.chainName?.toUpperCase()];
              return {
                ...s,
                cartTotal: chainInfo?.totalPrice ?? null,
                cartComplete: chainInfo?.complete ?? false,
                cartMissing: chainInfo?.missingProducts ?? [],
                cartItems: chainInfo?.items ?? [],
                hasCartInfo: !!chainInfo,
              };
            });
            setNearbyStores(merged);
          }
        }
      } catch (locErr) {
        console.log('Could not fetch nearby stores:', locErr.message);
      }
    } catch (err) { Alert.alert(t('error'), t('optimizationFailed')); }
    finally { setOptimizing(false); }
  };

  // ─── Swap ───
  const handleSwapClick = async (i) => {
    if (swapIndex === i) { setSwapIndex(null); setAlternatives([]); return; }
    setSwapIndex(i);
    setAlternatives([]);
    setLoadingAlts(true);
    const item = selectedStore.items[i];
    const term = item.searchTerm || (item.productName.includes(' → ') ? item.productName.split(' → ')[0] : item.productName);
    try {
      const res = await shoppingListService.getAlternatives(selectedStore.chainName, term, item.productId, user?.city);
      setAlternatives(res.data);
    } catch { /* ignore */ }
    finally { setLoadingAlts(false); }
  };

  const handleSwapSelect = (alt) => {
    const i = swapIndex;
    const oldItem = selectedStore.items[i];
    const newItem = {
      ...oldItem,
      productId: alt.productId,
      productName: (oldItem.searchTerm || oldItem.productName.split(' → ')[0]) + ' → ' + alt.productName,
      unitPrice: alt.unitPrice,
      subtotal: alt.unitPrice * oldItem.quantity,
    };
    const newItems = [...selectedStore.items];
    newItems[i] = newItem;
    const newTotal = newItems.reduce((sum, it) => sum + it.subtotal, 0);
    setSelectedStore({ ...selectedStore, items: newItems, totalPrice: newTotal });
    setSwapIndex(null);
    setAlternatives([]);
  };

  if (loading) return <View style={styles.center}><ActivityIndicator size="large" color="#5B4FE8" /></View>;
  if (!list) return <View style={styles.center}><Text>List not found</Text></View>;

  const completeResults = results ? results.filter((r) => r.complete) : [];
  const partialResults = results ? results.filter((r) => !r.complete) : [];

  // ═══ STORE CHECKLIST VIEW ═══
  if (selectedStore) {
    const allChecked = selectedStore.items.every((_, i) => checkedShop[i]);

    // Savings vs the most expensive complete store (only meaningful for the cheapest)
    const maxComplete = completeResults.length
      ? Math.max(...completeResults.map((r) => r.totalPrice)) : null;
    const savings = maxComplete && maxComplete > selectedStore.totalPrice
      ? maxComplete - selectedStore.totalPrice : 0;
    const savingsPct = savings && maxComplete ? Math.round((savings / maxComplete) * 100) : 0;
    const storeUrl = getChainUrl(selectedStore.chainName);

    return (
      <View style={styles.container}>
        <Stack.Screen options={{
          title: t('bestPlaceToShop'),
          headerShown: true,
          headerStyle: { backgroundColor: colors.hero },
          headerTintColor: '#fff',
          headerTitleStyle: { fontWeight: '700', color: '#fff' },
          headerShadowVisible: false,
          headerLeft: () => (
            <TouchableOpacity
              onPress={() => { setSelectedStore(null); setSwapIndex(null); setAlternatives([]); }}
              style={{ marginRight: 12, padding: 4 }}
            >
              <Ionicons name="arrow-back" size={24} color="#fff" />
            </TouchableOpacity>
          ),
        }} />

        {/* Navy hero banner */}
        <View style={styles.hero}>
          <View style={styles.heroTopRow}>
            <View style={styles.heroLogoBox}>
              <ChainLogo chainName={selectedStore.chainName} size={48} />
            </View>
            <View style={{ flex: 1, marginLeft: 12 }}>
              <Text style={styles.heroChain} numberOfLines={1}>{selectedStore.chainName}</Text>
              {savings > 0 ? (
                <Text style={styles.heroSave}>
                  {t('youllSave')} <Text style={styles.heroSaveAmt}>{savings.toFixed(2)} EUR ({savingsPct}%)</Text>
                </Text>
              ) : (
                selectedStore._storeName ? <Text style={styles.heroMetaSub} numberOfLines={1}>{selectedStore._storeName}</Text> : null
              )}
              <Text style={styles.heroMeta}>
                {selectedStore.items.length} {t('itemsLabel')} · {t('oneStop')}
              </Text>
            </View>
            <View style={styles.heroPill}>
              <Text style={styles.heroPillText}>{selectedStore.totalPrice.toFixed(2)} EUR</Text>
            </View>
          </View>
        </View>

        <Text style={styles.sectionHint}>{t('tapSwapHint')}</Text>

        {/* Total pinned at top */}
        <View style={[styles.totalBar, allChecked && { backgroundColor: '#d4edda' }]}>
          <Text style={styles.totalLabel}>{allChecked ? t('allDone') : t('estimatedTotal')}</Text>
          <Text style={[styles.totalAmount, selectedStore._isCheapest && { color: '#1f9d55' }]}>
            {selectedStore.totalPrice.toFixed(2)} EUR
          </Text>
        </View>

        <ScrollView style={{ flex: 1, marginTop: 8 }}>
          {selectedStore.items.map((item, i) => ({ item, i })).sort((a, b) => (checkedShop[a.i] ? 1 : 0) - (checkedShop[b.i] ? 1 : 0)).map(({ item, i }) => {
            const checked = !!checkedShop[i];
            const isSwapping = swapIndex === i;
            return (
              <View key={i}>
                <TouchableOpacity
                  activeOpacity={0.7}
                  onPress={() => { if (swapIndex === null) setCheckedShop((p) => ({ ...p, [i]: !p[i] })); }}
                  style={[styles.shopItem, checked && styles.shopItemChecked, isSwapping && styles.shopItemSwapping]}
                >
                  {/* Checkbox */}
                  <View style={[styles.checkbox, checked && styles.checkboxChecked]}>
                    {checked && <Ionicons name="checkmark" size={16} color="#fff" />}
                  </View>

                  {/* Name */}
                  <View style={{ flex: 1 }}>
                    <Text style={[styles.shopName, checked && styles.strikethrough]}>{item.productName}</Text>
                    <Text style={styles.shopQty}>x{item.quantity}</Text>
                  </View>

                  {/* Swap btn */}
                  <TouchableOpacity onPress={() => handleSwapClick(i)} style={[styles.swapBtn, isSwapping && styles.swapBtnActive]}>
                    <Ionicons name="swap-horizontal" size={18} color={isSwapping ? '#fff' : '#666'} />
                  </TouchableOpacity>

                  {/* Price */}
                  <View style={{ alignItems: 'flex-end' }}>
                    <Text style={[styles.shopPrice, checked && styles.strikethrough]}>{item.subtotal.toFixed(2)} EUR</Text>
                    {item.quantity > 1 && <Text style={styles.shopEa}>{item.unitPrice.toFixed(2)} ea</Text>}
                  </View>
                </TouchableOpacity>

                {/* Alternatives panel */}
                {isSwapping && (
                  <ScrollView style={styles.altPanel} nestedScrollEnabled>
                    {loadingAlts ? (
                      <ActivityIndicator size="small" color="#5B4FE8" style={{ padding: 12 }} />
                    ) : alternatives.filter((a) => a.productId !== item.productId).length === 0 ? (
                      <Text style={styles.altEmpty}>{t('noOtherOptions')}</Text>
                    ) : (
                      alternatives.filter((a) => a.productId !== item.productId).map((alt) => (
                        <TouchableOpacity key={alt.productId} style={styles.altRow} onPress={() => handleSwapSelect(alt)}>
                          <Text style={styles.altName} numberOfLines={2}>{alt.productName}</Text>
                          <Text style={styles.altPrice}>{alt.unitPrice.toFixed(2)} EUR</Text>
                        </TouchableOpacity>
                      ))
                    )}
                  </ScrollView>
                )}
              </View>
            );
          })}

          {selectedStore.missingProducts?.length > 0 && (
            <View style={styles.missingBar}>
              <Text style={styles.missingText}>{t('notAvailable')}: {selectedStore.missingProducts.join(', ')}</Text>
            </View>
          )}

          {storeUrl && (
            <TouchableOpacity style={styles.viewStoreBtn} onPress={() => Linking.openURL(storeUrl)}>
              <Text style={styles.viewStoreBtnText}>{t('viewInStore')}</Text>
              <Ionicons name="open-outline" size={18} color="#fff" />
            </TouchableOpacity>
          )}

          <View style={{ height: 30 }} />
        </ScrollView>
      </View>
    );
  }

  // ═══ MAIN LIST VIEW ═══
  return (
    <View style={styles.container}>
      <Stack.Screen options={{
        title: list.name,
        headerBackTitle: 'Lists',
        headerLeft: () => (
          <TouchableOpacity onPress={() => router.back()} style={{ marginRight: 12, padding: 4 }}>
            <Ionicons name="arrow-back" size={24} color="#fff" />
          </TouchableOpacity>
        ),
        headerRight: () => (
          <TouchableOpacity
            onPress={() => { setShareOpen(!shareOpen); if (!shareOpen) fetchMembers(); }}
            style={{ padding: 6 }}
          >
            <Ionicons name="people-outline" size={24} color="#fff" />
          </TouchableOpacity>
        ),
      }} />

      <ScrollView keyboardShouldPersistTaps="handled">
        {/* Share panel (toggled by header icon) */}
        {shareOpen && (
          <View style={styles.sharePanel}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
              <Text style={{ fontSize: 15, fontWeight: '700', color: '#333' }}>{t('shareThisList')}</Text>
              <TouchableOpacity onPress={() => setShareOpen(false)}>
                <Ionicons name="close" size={22} color="#999" />
              </TouchableOpacity>
            </View>
            <View style={{ flexDirection: 'row', gap: 8, marginBottom: 8 }}>
              <TextInput
                style={styles.shareInput}
                placeholder={t('enterEmail')}
                value={shareEmail}
                onChangeText={(t) => { setShareEmail(t); setShareError(''); }}
                onSubmitEditing={handleShare}
                keyboardType="email-address"
                autoCapitalize="none"
                autoFocus
                placeholderTextColor="#999"
              />
              <TouchableOpacity
                style={[styles.shareInviteBtn, !shareEmail.trim() && { backgroundColor: '#e9ecef' }]}
                onPress={handleShare}
                disabled={!shareEmail.trim()}
              >
                <Text style={{ color: shareEmail.trim() ? '#fff' : '#999', fontWeight: '700', fontSize: 14 }}>{t('invite')}</Text>
              </TouchableOpacity>
            </View>
            {!!shareError && <Text style={{ color: '#dc3545', fontSize: 12, marginBottom: 6 }}>{shareError}</Text>}

            <Text style={{ fontSize: 12, color: '#666', marginBottom: 6 }}>{t('members')} ({members.length + 1})</Text>
            <View style={styles.memberRow}>
              <View style={{ flex: 1 }}>
                <Text style={styles.memberName}>{list.user?.firstName} {list.user?.lastName}</Text>
                <Text style={styles.memberEmail}>{list.user?.email}</Text>
              </View>
              <View style={styles.ownerBadge}><Text style={styles.ownerBadgeText}>{t('owner')}</Text></View>
            </View>
            {members.map(m => (
              <View key={m.id} style={styles.memberRow}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.memberName}>{m.user?.firstName} {m.user?.lastName}</Text>
                  <Text style={styles.memberEmail}>{m.user?.email}</Text>
                </View>
                {isOwner && (
                  <TouchableOpacity onPress={() => handleRemoveMember(m.id)}>
                    <Text style={{ color: '#dc3545', fontSize: 13, fontWeight: '600' }}>{t('remove')}</Text>
                  </TouchableOpacity>
                )}
              </View>
            ))}
            {loadingMembers && <Text style={{ color: '#999', fontSize: 12, padding: 6 }}>{t('optimizing')}</Text>}
          </View>
        )}

        {/* Add item panel — appears at top when open */}
        {addOpen && (
          <View style={styles.addPanel}>
            <View style={styles.addRow}>
              <TextInput
                style={styles.addInput}
                placeholder={t('addItemPlaceholder')}
                value={searchQuery}
                onChangeText={handleSearchChange}
                onSubmitEditing={addGeneric}
                returnKeyType="done"
                autoFocus
                placeholderTextColor="#999"
              />
              <TouchableOpacity
                style={[styles.addSubmit, !searchQuery.trim() && { backgroundColor: '#e9ecef' }]}
                onPress={addGeneric}
                disabled={!searchQuery.trim()}
              >
                <Text style={{ color: searchQuery.trim() ? '#fff' : '#999', fontWeight: '700' }}>{t('add')}</Text>
              </TouchableOpacity>
              <TouchableOpacity onPress={() => { setAddOpen(false); setSearchQuery(''); setSearchResults([]); }}>
                <Ionicons name="close" size={24} color="#999" />
              </TouchableOpacity>
            </View>
            {searchResults.length > 0 && (
              <ScrollView style={styles.suggestions} nestedScrollEnabled keyboardShouldPersistTaps="handled">
                <Text style={styles.sugHeader}>{t('orPickProduct')}</Text>
                {searchResults.map((p) => (
                  <TouchableOpacity key={p.id} style={styles.sugRow} onPress={() => addSpecific(p.id)}>
                    {p.imageUrl ? (
                      <Image source={{ uri: p.imageUrl }} style={styles.sugImage} />
                    ) : (
                      <Text style={styles.sugEmoji}>{getCategoryIcon({ product: p })}</Text>
                    )}
                    <Text style={styles.sugName} numberOfLines={2}>{p.name}</Text>
                    {p.cheapestCurrentPrice != null && (
                      <Text style={styles.sugPrice}>{p.cheapestCurrentPrice.toFixed(2)} EUR</Text>
                    )}
                  </TouchableOpacity>
                ))}
              </ScrollView>
            )}
          </View>
        )}

        {/* Optimize button */}
        {items.length > 0 && (
          <TouchableOpacity
            style={[styles.optimizeBtn, optimizing && { backgroundColor: '#6c757d' }]}
            onPress={handleOptimize}
            disabled={optimizing}
          >
            {optimizing ? <ActivityIndicator color="#fff" /> : <Ionicons name="flash" size={20} color="#fff" />}
            <Text style={styles.optimizeBtnText}>{optimizing ? t('optimizing') : t('findCheapest')}</Text>
          </TouchableOpacity>
        )}

        {/* Items */}
        <Text style={styles.sectionTitle}>{t('itemsCount')} ({items.length})</Text>
        {items.length === 0 ? (
          <Text style={styles.emptyText}>{t('noItemsYet')}</Text>
        ) : (
          [...items].sort((a, b) => (checkedItems[a.id] ? 1 : 0) - (checkedItems[b.id] ? 1 : 0)).map((item) => {
            const checked = !!checkedItems[item.id];
            return (
              <View key={item.id} style={[styles.itemCard, checked && { opacity: 0.5 }]}>
                <TouchableOpacity onPress={() => setCheckedItems((p) => ({ ...p, [item.id]: !p[item.id] }))}>
                  {item.product?.imageUrl ? (
                    <Image source={{ uri: item.product.imageUrl }} style={styles.itemImage} />
                  ) : (
                    <Text style={styles.itemEmoji}>{getCategoryIcon(item)}</Text>
                  )}
                </TouchableOpacity>
                <TouchableOpacity
                  style={{ flex: 1 }}
                  onPress={() => setCheckedItems((p) => ({ ...p, [item.id]: !p[item.id] }))}
                >
                  <Text style={[styles.itemName, checked && styles.strikethrough]} numberOfLines={2}>
                    {item.product ? item.product.name : item.searchTerm}
                  </Text>
                </TouchableOpacity>
                <View style={styles.qtyRow}>
                  <TouchableOpacity style={styles.qtyBtn} onPress={() => changeQty(item.id, item.quantity - 1)}>
                    <Text style={styles.qtyBtnText}>-</Text>
                  </TouchableOpacity>
                  <Text style={styles.qtyVal}>{item.quantity}</Text>
                  <TouchableOpacity style={styles.qtyBtn} onPress={() => changeQty(item.id, item.quantity + 1)}>
                    <Text style={styles.qtyBtnText}>+</Text>
                  </TouchableOpacity>
                </View>
                <TouchableOpacity style={styles.trashBtn} onPress={() => removeItem(item.id)}>
                  <Ionicons name="trash" size={16} color="#fff" />
                </TouchableOpacity>
              </View>
            );
          })
        )}

        {/* Optimization results — store cards */}
        {results && (
          <View style={{ marginTop: 20 }}>
            {/* Nearby Stores FIRST — most relevant to the user */}
            {nearbyStores.length > 0 && (
              <View style={{ marginTop: 20 }}>
                <View style={styles.nearbyHeader}>
                  <Text style={[styles.sectionTitle, { marginBottom: 0 }]}>{t('nearbyStores')}</Text>
                  {MapView && (
                    <TouchableOpacity
                      style={styles.viewToggle}
                      onPress={() => setStoreViewMode(storeViewMode === 'list' ? 'map' : 'list')}
                    >
                      <Ionicons name={storeViewMode === 'list' ? 'map' : 'list'} size={18} color="#5B4FE8" />
                      <Text style={styles.viewToggleText}>{storeViewMode === 'list' ? t('map') : t('list')}</Text>
                    </TouchableOpacity>
                  )}
                </View>
                <Text style={styles.sectionHint}>{t('tapStoreHint')}</Text>

                {(() => {
                  // Find the cheapest chain name from optimization results
                  const cheapestChain = completeResults.length > 0 ? completeResults[0].chainName.toUpperCase() : null;

                  const renderStoreCard = (s, i) => {
                    const isCheapestChain = cheapestChain && s.chainName?.toUpperCase() === cheapestChain;
                    return (
                      <TouchableOpacity
                        key={`${s.id}-${i}`}
                        style={[styles.nearbyCard, isCheapestChain && styles.cheapestBorder]}
                        onPress={() => {
                          if (!s.hasCartInfo) return;
                          setCheckedShop({});
                          setSwapIndex(null);
                          setSelectedStore({
                            chainName: s.chainName,
                            totalPrice: s.cartTotal,
                            complete: s.cartComplete,
                            missingProducts: s.cartMissing,
                            items: s.cartItems,
                            _isCheapest: isCheapestChain,
                            _storeName: s.name,
                          });
                        }}
                      >
                        <ChainLogo chainName={s.chainName} size={40} />
                        <View style={{ flex: 1, marginLeft: 12 }}>
                          <Text style={styles.nearbyName} numberOfLines={1}>{s.name}</Text>
                          <View style={styles.nearbyMeta}>
                            <Text style={styles.nearbyChain}>{s.chainName}</Text>
                            <Text style={styles.nearbyDist}>
                              <Ionicons name="location" size={11} color="#888" /> {s.distanceKm.toFixed(1)} km
                            </Text>
                          </View>
                          {s.hasCartInfo && !s.cartComplete && s.cartMissing?.length > 0 && (
                            <Text style={styles.nearbyMissing} numberOfLines={1}>
                              {t('missing')}: {s.cartMissing.join(', ')}
                            </Text>
                          )}
                        </View>
                        <View style={{ alignItems: 'flex-end' }}>
                          {isCheapestChain && <Text style={styles.cheapestBadge}>{t('cheapest')}</Text>}
                          {s.hasCartInfo ? (
                            <Text style={[styles.nearbyPrice, isCheapestChain && { color: '#1f9d55' }]}>
                              {s.cartTotal.toFixed(2)} EUR
                            </Text>
                          ) : (
                            <Text style={styles.nearbyNoCart}>{t('noPrices')}</Text>
                          )}
                        </View>
                      </TouchableOpacity>
                    );
                  };

                  if (storeViewMode === 'map' && MapView) {
                    return (
                      <>
                        <View style={styles.nearbyMap}>
                          <MapView
                            style={{ height: 300, borderRadius: 10 }}
                            initialRegion={{
                              latitude: nearbyStores[0].latitude,
                              longitude: nearbyStores[0].longitude,
                              latitudeDelta: 0.5,
                              longitudeDelta: 0.5,
                            }}
                            showsUserLocation={true}
                          >
                            {nearbyStores.map((s, i) => (
                              s.latitude != null && s.longitude != null && (
                                <Marker
                                  key={`${s.id}-${i}`}
                                  coordinate={{ latitude: s.latitude, longitude: s.longitude }}
                                  title={s.name}
                                  description={s.hasCartInfo ? `Cart: ${s.cartTotal.toFixed(2)} EUR · ${s.distanceKm.toFixed(1)} km` : `${s.distanceKm.toFixed(1)} km`}
                                  pinColor={cheapestChain && s.chainName?.toUpperCase() === cheapestChain ? '#1f9d55' : '#dc3545'}
                                />
                              )
                            ))}
                          </MapView>
                        </View>
                        {nearbyStores.map((s, i) => renderStoreCard(s, i))}
                      </>
                    );
                  }

                  return nearbyStores.map((s, i) => renderStoreCard(s, i));
                })()}
              </View>
            )}

            {/* Best Chains — overall cheapest by chain */}
            {completeResults.length > 0 && (() => {
              const cheapestTotal = completeResults[0].totalPrice;
              return (
                <>
                  <Text style={[styles.sectionTitle, { color: '#1f9d55', marginTop: 20 }]}>{t('bestChains')}</Text>
                  <Text style={styles.sectionHint}>{t('tapStoreHint')}</Text>
                  {completeResults.map((store) => {
                    const isCheapest = store.totalPrice === cheapestTotal;
                    return (
                      <TouchableOpacity
                        key={store.chainName}
                        style={[styles.storeCard, isCheapest && styles.cheapestBorder]}
                        onPress={() => { setCheckedShop({}); setSwapIndex(null); setSelectedStore({ ...store, _isCheapest: isCheapest }); }}
                      >
                        <View>
                          <Text style={styles.storeCardName}>
                            {isCheapest && <Text style={{ color: '#1f9d55' }}>{t('cheapest')} </Text>}
                            {store.chainName}
                          </Text>
                          <Text style={styles.storeCardSub}>{store.items.length} items</Text>
                        </View>
                        <Text style={[styles.storeCardPrice, isCheapest && { color: '#1f9d55' }]}>
                          {store.totalPrice.toFixed(2)} EUR
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </>
              );
            })()}

            {partialResults.length > 0 && (
              <>
                <Text style={[styles.sectionTitle, { color: '#856404', marginTop: 16 }]}>{t('partialMatches')}</Text>
                {partialResults.map((store) => (
                  <TouchableOpacity
                    key={store.chainName}
                    style={[styles.storeCard, { borderColor: '#ffc107', backgroundColor: '#fffdf0' }]}
                    onPress={() => { setCheckedShop({}); setSwapIndex(null); setSelectedStore({ ...store, _isCheapest: false }); }}
                  >
                    <View>
                      <Text style={styles.storeCardName}>{store.chainName}</Text>
                      <Text style={{ color: '#dc3545', fontSize: 12 }}>{t('missing')}: {store.missingProducts.join(', ')}</Text>
                    </View>
                    <Text style={[styles.storeCardPrice, { color: '#856404' }]}>{store.totalPrice.toFixed(2)} EUR</Text>
                  </TouchableOpacity>
                ))}
              </>
            )}
          </View>
        )}

        <View style={{ height: 80 }} />
      </ScrollView>

      {/* Floating + button */}
      {!addOpen && (
        <TouchableOpacity
          style={styles.fab}
          onPress={() => setAddOpen(true)}
          activeOpacity={0.8}
        >
          <Ionicons name="add" size={30} color="#fff" />
        </TouchableOpacity>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5', padding: 14 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center' },

  // Share
  sharePanel: { backgroundColor: '#fff', borderRadius: 10, padding: 14, marginBottom: 12, borderWidth: 1, borderColor: '#ddd' },
  shareInput: { flex: 1, borderWidth: 1, borderColor: '#ddd', borderRadius: 8, padding: 10, fontSize: 14, color: '#333' },
  shareInviteBtn: { backgroundColor: '#5B4FE8', paddingHorizontal: 16, paddingVertical: 10, borderRadius: 8, justifyContent: 'center' },
  memberRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 8, borderBottomWidth: 1, borderBottomColor: '#f0f0f0' },
  memberName: { fontSize: 14, fontWeight: '500', color: '#333' },
  memberEmail: { fontSize: 12, color: '#888' },
  ownerBadge: { backgroundColor: '#ECEAFB', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 4 },
  ownerBadgeText: { fontSize: 11, color: '#5B4FE8', fontWeight: '600' },

  // Add item
  addPanel: { backgroundColor: '#fff', borderRadius: 10, padding: 12, marginBottom: 12 },
  addRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  addInput: { flex: 1, borderWidth: 2, borderColor: '#ddd', borderRadius: 8, padding: 10, fontSize: 15, color: '#333' },
  addSubmit: { backgroundColor: '#5B4FE8', paddingHorizontal: 14, paddingVertical: 10, borderRadius: 8 },
  suggestions: { marginTop: 8, maxHeight: 250, borderTopWidth: 1, borderTopColor: '#eee' },
  sugHeader: { fontSize: 12, color: '#888', padding: 8, paddingBottom: 4 },
  sugRow: { flexDirection: 'row', alignItems: 'center', padding: 10, borderBottomWidth: 1, borderBottomColor: '#f5f5f5' },
  sugImage: { width: 52, height: 52, borderRadius: 8, marginRight: 10, backgroundColor: '#f5f5f5' },
  sugEmoji: { fontSize: 28, marginRight: 10, width: 52, textAlign: 'center' },
  sugName: { flex: 1, fontSize: 14, color: '#333' },
  sugPrice: { color: '#1f9d55', fontWeight: '600', fontSize: 13, marginLeft: 8 },

  // Optimize
  optimizeBtn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, backgroundColor: '#5B4FE8', padding: 14, borderRadius: 10, marginBottom: 12 },
  optimizeBtnText: { color: '#fff', fontSize: 16, fontWeight: '700' },

  // Items
  sectionTitle: { fontSize: 18, fontWeight: '700', color: '#333', marginBottom: 8 },
  sectionHint: { fontSize: 12, color: '#999', marginBottom: 8 },
  emptyText: { color: '#999', textAlign: 'center', marginTop: 20 },
  itemCard: { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: '#fff', padding: 10, borderRadius: 10, marginBottom: 4, borderWidth: 1, borderColor: '#eee' },
  itemEmoji: { fontSize: 28, width: 52, textAlign: 'center' },
  itemImage: { width: 52, height: 52, borderRadius: 8, backgroundColor: '#f5f5f5' },
  itemName: { fontSize: 14, fontWeight: '500', color: '#333' },
  qtyRow: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  qtyBtn: { width: 28, height: 28, backgroundColor: '#f0f0f0', borderRadius: 6, alignItems: 'center', justifyContent: 'center' },
  qtyBtnText: { fontSize: 16, fontWeight: '700', color: '#555' },
  qtyVal: { fontWeight: '700', fontSize: 14, minWidth: 20, textAlign: 'center', color: '#333' },
  trashBtn: { width: 30, height: 30, backgroundColor: '#dc3545', borderRadius: 6, alignItems: 'center', justifyContent: 'center' },

  // Store cards
  storeCard: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', backgroundColor: '#fff', padding: 16, borderRadius: 12, marginBottom: 8, borderWidth: 1, borderColor: '#eee' },
  storeCardName: { fontSize: 16, fontWeight: '600', color: '#333' },
  storeCardSub: { fontSize: 12, color: '#888', marginTop: 2 },
  storeCardPrice: { fontSize: 22, fontWeight: '700', color: '#1f9d55' },
  cheapestBorder: { borderWidth: 2, borderColor: '#1f9d55', backgroundColor: '#f0fff4' },

  // Best-store navy hero
  hero: { backgroundColor: '#1E2A78', borderRadius: 16, padding: 18, marginBottom: 12 },
  heroTopRow: { flexDirection: 'row', alignItems: 'center' },
  heroLogoBox: { backgroundColor: '#fff', borderRadius: 12, padding: 6 },
  heroChain: { color: '#fff', fontSize: 24, fontWeight: '800' },
  heroSave: { color: 'rgba(255,255,255,0.85)', fontSize: 13, marginTop: 2 },
  heroSaveAmt: { color: '#7CF0AE', fontWeight: '800' },
  heroMetaSub: { color: 'rgba(255,255,255,0.8)', fontSize: 13, marginTop: 2 },
  heroMeta: { color: 'rgba(255,255,255,0.7)', fontSize: 12, marginTop: 4 },
  heroPill: { backgroundColor: '#fff', borderRadius: 20, paddingHorizontal: 16, paddingVertical: 8, marginLeft: 8 },
  heroPillText: { color: '#1f9d55', fontSize: 16, fontWeight: '800' },
  viewStoreBtn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, backgroundColor: '#1E2A78', padding: 16, borderRadius: 14, marginTop: 14 },
  viewStoreBtnText: { color: '#fff', fontSize: 16, fontWeight: '700' },

  // Store checklist
  storeHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', backgroundColor: '#fff', padding: 16, borderRadius: 12, marginBottom: 10, borderWidth: 1, borderColor: '#ddd' },
  cheapestBadge: { color: '#1f9d55', fontSize: 12, fontWeight: '700' },
  storeTitle: { fontSize: 20, fontWeight: '700', color: '#333' },
  storeSubtitle: { fontSize: 12, color: '#888', marginTop: 2 },
  storeTotal: { fontSize: 24, fontWeight: '700', color: '#1f9d55' },
  shopItem: { flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: '#fff', padding: 12, borderRadius: 10, marginBottom: 2, borderWidth: 1, borderColor: '#eee' },
  shopItemChecked: { backgroundColor: '#f9f9f9', opacity: 0.6 },
  shopItemSwapping: { borderColor: '#5B4FE8', borderWidth: 2, borderBottomLeftRadius: 0, borderBottomRightRadius: 0, marginBottom: 0 },
  checkbox: { width: 24, height: 24, borderRadius: 6, borderWidth: 2, borderColor: '#ccc', alignItems: 'center', justifyContent: 'center' },
  checkboxChecked: { backgroundColor: '#5B4FE8', borderColor: '#5B4FE8' },
  shopName: { fontSize: 14, fontWeight: '500', color: '#333' },
  shopQty: { fontSize: 12, color: '#888', marginTop: 1 },
  shopPrice: { fontSize: 15, fontWeight: '700', color: '#333' },
  shopEa: { fontSize: 11, color: '#999' },
  swapBtn: { width: 30, height: 30, borderRadius: 6, backgroundColor: '#f0f0f0', alignItems: 'center', justifyContent: 'center' },
  swapBtnActive: { backgroundColor: '#5B4FE8' },
  strikethrough: { textDecorationLine: 'line-through', color: '#aaa' },

  // Back buttons
  backRow: { flexDirection: 'row', gap: 8, marginBottom: 10 },
  backBtnGray: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, backgroundColor: '#f0f0f0', padding: 12, borderRadius: 10 },
  backBtnGrayText: { fontSize: 14, fontWeight: '600', color: '#333' },
  backBtnGreen: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, backgroundColor: '#5B4FE8', padding: 12, borderRadius: 10 },
  backBtnGreenText: { fontSize: 14, fontWeight: '600', color: '#fff' },

  // Alternatives
  altPanel: { borderWidth: 2, borderTopWidth: 0, borderColor: '#5B4FE8', borderBottomLeftRadius: 10, borderBottomRightRadius: 10, backgroundColor: '#fff', marginBottom: 4, maxHeight: 200, overflow: 'hidden' },
  altEmpty: { padding: 14, textAlign: 'center', color: '#999', fontSize: 13 },
  altRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 12, borderBottomWidth: 1, borderBottomColor: '#f0f0f0' },
  altName: { flex: 1, fontSize: 13, color: '#333' },
  altPrice: { fontWeight: '700', color: '#1f9d55', fontSize: 14, marginLeft: 8 },

  // Total
  totalBar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 16, borderRadius: 10, backgroundColor: '#f8f9fa', marginTop: 12, borderWidth: 1, borderColor: '#ddd' },
  totalLabel: { fontWeight: '700', fontSize: 16, color: '#333' },
  totalAmount: { fontWeight: '700', fontSize: 22, color: '#1f9d55' },
  missingBar: { marginTop: 8, padding: 12, borderRadius: 8, backgroundColor: '#fff3cd', borderWidth: 1, borderColor: '#ffc107' },
  missingText: { fontSize: 13, color: '#856404' },

  // Nearby stores
  nearbyHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 },
  viewToggle: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 6, borderRadius: 6, backgroundColor: '#ECEAFB' },
  viewToggleText: { fontSize: 12, fontWeight: '600', color: '#5B4FE8' },
  nearbyMap: { borderRadius: 10, overflow: 'hidden', marginBottom: 8 },
  nearbyCard: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#fff', padding: 14, borderRadius: 10, marginBottom: 6, borderWidth: 1, borderColor: '#eee' },
  nearbyName: { fontSize: 14, fontWeight: '600', color: '#333' },
  nearbyMeta: { flexDirection: 'row', gap: 10, marginTop: 3 },
  nearbyChain: { fontSize: 12, color: '#666' },
  nearbyDist: { fontSize: 12, color: '#888' },
  nearbyMissing: { fontSize: 11, color: '#dc3545', marginTop: 2 },
  nearbyPrice: { fontSize: 18, fontWeight: '700', color: '#1f9d55' },
  nearbyNoCart: { fontSize: 12, color: '#999', fontStyle: 'italic' },

  // FAB
  fab: { position: 'absolute', right: 20, bottom: 20, width: 56, height: 56, borderRadius: 28, backgroundColor: '#5B4FE8', alignItems: 'center', justifyContent: 'center', elevation: 6, shadowColor: '#000', shadowOffset: { width: 0, height: 3 }, shadowOpacity: 0.3, shadowRadius: 4 },
});
