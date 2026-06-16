import { useState, useCallback } from 'react';
import { View, Text, FlatList, TextInput, TouchableOpacity, StyleSheet, Alert } from 'react-native';
import { useRouter, useFocusEffect } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { shoppingListService } from '../../services/api';
import { useAuth } from '../../services/AuthContext';
import { useI18n } from '../../services/i18n';

export default function Lists() {
  const { user } = useAuth();
  const router = useRouter();
  const { t } = useI18n();
  const [lists, setLists] = useState([]);
  const [newName, setNewName] = useState('');
  const [loading, setLoading] = useState(true);

  const fetchLists = async () => {
    try {
      const res = await shoppingListService.getForUser(user.id);
      setLists(res.data);
    } catch (err) {
      console.error('Failed to load lists:', err);
    } finally {
      setLoading(false);
    }
  };

  useFocusEffect(useCallback(() => { fetchLists(); }, []));

  const handleCreate = async () => {
    if (!newName.trim()) return;
    try {
      await shoppingListService.create(newName.trim(), user.id);
      setNewName('');
      fetchLists();
    } catch (err) {
      Alert.alert(t('error'), t('failedToCreate'));
    }
  };

  const handleDelete = (listId, name) => {
    Alert.alert(t('deleteList'), t('deleteListConfirm').replace('{name}', name), [
      { text: t('cancel'), style: 'cancel' },
      {
        text: t('delete'), style: 'destructive',
        onPress: async () => {
          try {
            await shoppingListService.deleteList(listId);
            fetchLists();
          } catch (err) {
            Alert.alert(t('error'), t('failedToDelete'));
          }
        },
      },
    ]);
  };

  const renderItem = ({ item }) => {
    const isShared = item.user?.id !== user.id;
    return (
      <TouchableOpacity
        style={styles.card}
        onPress={() => router.push(`/list/${item.id}`)}
        activeOpacity={0.7}
      >
        <View style={{ flex: 1 }}>
          <Text style={styles.cardTitle}>{item.name}</Text>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 2 }}>
            <Text style={styles.cardSub}>{item.items?.length || 0} {t('items')}</Text>
            {isShared && (
              <View style={{ backgroundColor: '#ECEAFB', paddingHorizontal: 6, paddingVertical: 1, borderRadius: 4 }}>
                <Text style={{ fontSize: 11, color: '#5B4FE8' }}>{t('sharedBy')} {item.user?.firstName}</Text>
              </View>
            )}
            {!isShared && item.members?.length > 0 && (
              <View style={{ backgroundColor: '#f0f0f0', paddingHorizontal: 6, paddingVertical: 1, borderRadius: 4 }}>
                <Text style={{ fontSize: 11, color: '#666' }}>{t('shared')}</Text>
              </View>
            )}
          </View>
        </View>
        <TouchableOpacity onPress={() => handleDelete(item.id, item.name)} hitSlop={8}>
          <Ionicons name="trash-outline" size={22} color="#dc3545" />
        </TouchableOpacity>
      </TouchableOpacity>
    );
  };

  return (
    <View style={styles.container}>
      <View style={styles.createRow}>
        <TextInput
          style={styles.input}
          placeholder={t('newListPlaceholder')}
          value={newName}
          onChangeText={setNewName}
          onSubmitEditing={handleCreate}
          returnKeyType="done"
          placeholderTextColor="#999"
        />
        <TouchableOpacity style={styles.createBtn} onPress={handleCreate}>
          <Ionicons name="add" size={24} color="#fff" />
        </TouchableOpacity>
      </View>

      <FlatList
        data={lists}
        keyExtractor={(item) => item.id.toString()}
        renderItem={renderItem}
        contentContainerStyle={lists.length === 0 ? styles.emptyContainer : { paddingBottom: 20 }}
        ListEmptyComponent={
          !loading && <Text style={styles.empty}>{t('noListsYet')}</Text>
        }
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5', padding: 16 },
  createRow: { flexDirection: 'row', gap: 10, marginBottom: 16 },
  input: { flex: 1, borderWidth: 2, borderColor: '#ddd', borderRadius: 10, padding: 12, fontSize: 16, backgroundColor: '#fff', color: '#333' },
  createBtn: { backgroundColor: '#5B4FE8', width: 50, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  card: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#fff', padding: 16, borderRadius: 12, marginBottom: 8, borderWidth: 1, borderColor: '#eee' },
  cardTitle: { fontSize: 17, fontWeight: '600', color: '#1E2A78' },
  cardSub: { fontSize: 13, color: '#888', marginTop: 2 },
  emptyContainer: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  empty: { color: '#999', fontSize: 15, textAlign: 'center' },
});
