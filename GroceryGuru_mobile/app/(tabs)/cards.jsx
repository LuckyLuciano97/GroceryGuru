import { useMemo, useState, useEffect, useCallback } from 'react';
import { View, Text, StyleSheet, ScrollView, TouchableOpacity, Alert, Image, TextInput, ActivityIndicator } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useI18n } from '../../services/i18n';
import { colors } from '../../services/theme';
import { getChainLogo, getChainColor, getChainInitial, KNOWN_CHAINS } from '../../services/chains';
import { loyaltyCardService, storeService } from '../../services/api';
import { locationService } from '../../services/location';

// Faux barcode rendered from Views (no native barcode lib yet).
function Barcode({ value, color = '#1A1A2E' }) {
  const bars = useMemo(() => {
    const seed = String(value || '0000');
    const out = [];
    for (let i = 0; i < 48; i++) {
      const c = seed.charCodeAt(i % seed.length) + i * 7;
      out.push({ w: (c % 3) + 1, on: c % 4 !== 0 });
    }
    return out;
  }, [value]);
  return (
    <View style={styles.barcode}>
      {bars.map((b, i) => (
        <View key={i} style={{ width: b.w * 2, height: 54, backgroundColor: b.on ? color : 'transparent' }} />
      ))}
    </View>
  );
}

function LoyaltyCard({ card, featured, onDelete }) {
  const logo = getChainLogo(card.chain);
  const accent = getChainColor(card.chain);
  const { t } = useI18n();
  return (
    <View style={[styles.card, featured && styles.cardFeatured]}>
      <View style={styles.cardTop}>
        <View style={[styles.logoBox, { backgroundColor: logo ? '#fff' : accent }]}>
          {logo
            ? <Image source={logo} style={styles.logoImg} resizeMode="contain" />
            : <Text style={styles.logoInitial}>{getChainInitial(card.chain)}</Text>}
        </View>
        <View style={{ flex: 1, marginLeft: 12 }}>
          <Text style={styles.cardChain}>{card.chain}</Text>
          {card.sample
            ? <Text style={styles.sampleTag}>{t('sampleCard')}</Text>
            : featured && <Text style={styles.nearTag}>{t('nearestStore')}</Text>}
        </View>
        {onDelete && (
          <TouchableOpacity onPress={onDelete} hitSlop={10}>
            <Ionicons name="trash-outline" size={20} color={colors.textMuted} />
          </TouchableOpacity>
        )}
      </View>

      <Barcode value={card.number} />
      <Text style={styles.cardNumber}>{card.number}</Text>
      {featured && <Text style={styles.checkoutHint}>{t('showAtCheckout')}</Text>}
    </View>
  );
}

const SAMPLE = { id: 'sample', chain: 'Konzum', number: '9 8410 7723 0041', sample: true };

export default function CardsScreen() {
  const { t } = useI18n();
  const [cards, setCards] = useState([]);
  const [loading, setLoading] = useState(true);
  const [nearestChain, setNearestChain] = useState(null);
  const [adding, setAdding] = useState(false);
  const [pickChain, setPickChain] = useState(null);
  const [number, setNumber] = useState('');
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const res = await loyaltyCardService.list();
      setCards(res.data || []);
    } catch { /* keep empty */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  // Find the nearest store's chain so we can promote its card to the top.
  useEffect(() => {
    (async () => {
      try {
        if (!(await locationService.requestPermission())) return;
        const coords = await locationService.getCurrentLocation();
        if (!coords) return;
        const res = await storeService.getNearbyStores(coords.latitude, coords.longitude, 25);
        const nearest = (res.data || [])[0];
        if (nearest?.chainName) setNearestChain(nearest.chainName);
      } catch { /* geolocation optional */ }
    })();
  }, []);

  const handleAdd = async () => {
    if (!pickChain || !number.trim()) return;
    setSaving(true);
    try {
      await loyaltyCardService.add(pickChain, number.trim());
      setAdding(false); setPickChain(null); setNumber('');
      setLoading(true); await load();
    } catch { Alert.alert(t('error') || 'Error', t('failedToAdd') || 'Could not add card'); }
    finally { setSaving(false); }
  };

  const handleDelete = (card) => {
    Alert.alert(card.chain, '', [
      { text: t('cancel') || 'Cancel', style: 'cancel' },
      {
        text: t('remove') || 'Remove', style: 'destructive',
        onPress: async () => {
          try { await loyaltyCardService.remove(card.id); load(); } catch { /* ignore */ }
        },
      },
    ]);
  };

  if (loading) {
    return <View style={styles.centered}><ActivityIndicator size="large" color={colors.primary} /></View>;
  }

  const hasReal = cards.length > 0;
  // featured = card matching the nearest store, else the first card; sample if none
  const featured = hasReal
    ? (cards.find((c) => nearestChain && c.chain.toLowerCase() === nearestChain.toLowerCase()) || cards[0])
    : SAMPLE;
  const rest = hasReal ? cards.filter((c) => c.id !== featured.id) : [];

  return (
    <ScrollView style={styles.screen} contentContainerStyle={{ padding: 16, paddingBottom: 32 }}>
      {/* How-to banner */}
      <View style={styles.infoBanner}>
        <View style={styles.infoHeaderRow}>
          <Ionicons name="information-circle" size={20} color={colors.primary} />
          <Text style={styles.infoTitle}>{t('howToAdd')}</Text>
        </View>
        {[t('howToStep1'), t('howToStep2'), t('howToStep3')].map((step, i) => (
          <View key={i} style={styles.stepRow}>
            <View style={styles.stepNum}><Text style={styles.stepNumText}>{i + 1}</Text></View>
            <Text style={styles.stepText}>{step}</Text>
          </View>
        ))}
      </View>

      {/* Add form */}
      {adding ? (
        <View style={styles.addForm}>
          <Text style={styles.addFormTitle}>{t('addCard')}</Text>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} style={{ marginVertical: 10 }}>
            {KNOWN_CHAINS.map((c) => {
              const logo = getChainLogo(c);
              const sel = pickChain === c;
              return (
                <TouchableOpacity key={c} style={[styles.chainChip, sel && styles.chainChipSel]} onPress={() => setPickChain(c)}>
                  {logo
                    ? <Image source={logo} style={{ width: 26, height: 26, borderRadius: 6 }} resizeMode="contain" />
                    : <View style={[styles.chipInit, { backgroundColor: getChainColor(c) }]}><Text style={{ color: '#fff', fontWeight: '800' }}>{getChainInitial(c)}</Text></View>}
                  <Text style={[styles.chipText, sel && { color: colors.primary, fontWeight: '700' }]}>{c}</Text>
                </TouchableOpacity>
              );
            })}
          </ScrollView>
          <TextInput
            style={styles.numberInput}
            placeholder={t('howToStep2')}
            value={number}
            onChangeText={setNumber}
            placeholderTextColor={colors.textMuted}
            keyboardType="numbers-and-punctuation"
          />
          <View style={{ flexDirection: 'row', gap: 8, marginTop: 12 }}>
            <TouchableOpacity style={[styles.formBtn, styles.formCancel]} onPress={() => { setAdding(false); setPickChain(null); setNumber(''); }}>
              <Text style={styles.formCancelText}>{t('cancel') || 'Cancel'}</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.formBtn, styles.formSave, (!pickChain || !number.trim()) && { opacity: 0.5 }]}
              onPress={handleAdd}
              disabled={!pickChain || !number.trim() || saving}
            >
              {saving ? <ActivityIndicator color="#fff" /> : <Text style={styles.formSaveText}>{t('addCard')}</Text>}
            </TouchableOpacity>
          </View>
        </View>
      ) : (
        <TouchableOpacity style={styles.addBtn} onPress={() => setAdding(true)}>
          <Ionicons name="add-circle-outline" size={20} color="#fff" />
          <Text style={styles.addBtnText}>{t('addCard')}</Text>
        </TouchableOpacity>
      )}

      {/* Featured */}
      <Text style={[styles.sectionLabel, { marginTop: 22 }]}>{hasReal ? t('nearestStore') : t('yourCards')}</Text>
      <LoyaltyCard card={featured} featured onDelete={hasReal ? () => handleDelete(featured) : undefined} />
      {!hasReal && <Text style={styles.emptyHint}>{t('noCardsHint')}</Text>}

      {/* Other cards */}
      {rest.length > 0 && (
        <>
          <Text style={[styles.sectionLabel, { marginTop: 20 }]}>{t('yourCards')}</Text>
          {rest.map((c) => <LoyaltyCard key={c.id} card={c} onDelete={() => handleDelete(c)} />)}
        </>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bgMuted },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: colors.bgMuted },

  infoBanner: { backgroundColor: colors.primarySoft, borderRadius: 16, padding: 16, marginBottom: 16 },
  infoHeaderRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 12 },
  infoTitle: { fontSize: 15, fontWeight: '800', color: colors.text, marginLeft: 8 },
  stepRow: { flexDirection: 'row', alignItems: 'flex-start', marginBottom: 10 },
  stepNum: { width: 22, height: 22, borderRadius: 11, backgroundColor: colors.primary, alignItems: 'center', justifyContent: 'center', marginRight: 10 },
  stepNumText: { color: '#fff', fontSize: 12, fontWeight: '800' },
  stepText: { flex: 1, fontSize: 13.5, color: '#4A4A66', lineHeight: 19 },

  sectionLabel: { fontSize: 13, fontWeight: '700', color: colors.textMuted, textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 10 },
  emptyHint: { fontSize: 13, color: colors.textMuted, textAlign: 'center', marginTop: 4, paddingHorizontal: 20, lineHeight: 18 },

  card: { backgroundColor: colors.bg, borderRadius: 18, padding: 18, borderWidth: 1, borderColor: colors.border, marginBottom: 12 },
  cardFeatured: { borderWidth: 2, borderColor: colors.primary, shadowColor: colors.primary, shadowOpacity: 0.12, shadowRadius: 12, shadowOffset: { width: 0, height: 4 }, elevation: 3 },
  cardTop: { flexDirection: 'row', alignItems: 'center', marginBottom: 16 },
  logoBox: { width: 46, height: 46, borderRadius: 10, alignItems: 'center', justifyContent: 'center', borderWidth: 1, borderColor: colors.border },
  logoImg: { width: 38, height: 38, borderRadius: 8 },
  logoInitial: { color: '#fff', fontWeight: '800', fontSize: 20 },
  cardChain: { fontSize: 18, fontWeight: '800', color: colors.text },
  sampleTag: { fontSize: 11, fontWeight: '700', color: colors.primary, backgroundColor: colors.primarySoft, alignSelf: 'flex-start', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 6, marginTop: 4, overflow: 'hidden' },
  nearTag: { fontSize: 11, fontWeight: '700', color: colors.save, backgroundColor: colors.saveSoft, alignSelf: 'flex-start', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 6, marginTop: 4, overflow: 'hidden' },

  barcode: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', height: 54, gap: 2, marginVertical: 4 },
  cardNumber: { textAlign: 'center', fontSize: 15, fontWeight: '700', letterSpacing: 2, color: colors.text, marginTop: 10 },
  checkoutHint: { textAlign: 'center', fontSize: 12, color: colors.textMuted, marginTop: 8 },

  addBtn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: colors.primary, borderRadius: 14, paddingVertical: 16, gap: 8 },
  addBtnText: { color: '#fff', fontSize: 16, fontWeight: '700' },

  addForm: { backgroundColor: colors.bg, borderRadius: 16, padding: 16, borderWidth: 1, borderColor: colors.border },
  addFormTitle: { fontSize: 16, fontWeight: '800', color: colors.text },
  chainChip: { alignItems: 'center', justifyContent: 'center', paddingHorizontal: 12, paddingVertical: 10, marginRight: 8, borderRadius: 12, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.bg, minWidth: 70 },
  chainChipSel: { borderColor: colors.primary, backgroundColor: colors.primarySoft },
  chipInit: { width: 26, height: 26, borderRadius: 6, alignItems: 'center', justifyContent: 'center' },
  chipText: { fontSize: 12, color: colors.text, marginTop: 4 },
  numberInput: { borderWidth: 1, borderColor: colors.border, borderRadius: 10, padding: 12, fontSize: 15, color: colors.text },
  formBtn: { flex: 1, paddingVertical: 14, borderRadius: 12, alignItems: 'center' },
  formCancel: { backgroundColor: colors.bgMuted },
  formCancelText: { color: colors.text, fontWeight: '700' },
  formSave: { backgroundColor: colors.primary },
  formSaveText: { color: '#fff', fontWeight: '700' },
});
