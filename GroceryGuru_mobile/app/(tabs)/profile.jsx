import { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, Alert, ScrollView } from 'react-native';
import { useRouter } from 'expo-router';
import { useAuth } from '../../services/AuthContext';
import { userService } from '../../services/api';
import { useI18n } from '../../services/i18n';
import { Ionicons } from '@expo/vector-icons';

export default function Profile() {
  const { user, logout, updateUser } = useAuth();
  const router = useRouter();
  const { t, language, setLanguage } = useI18n();

  // Edit profile
  const [editing, setEditing] = useState(false);
  const [firstName, setFirstName] = useState(user?.firstName || '');
  const [lastName, setLastName] = useState(user?.lastName || '');
  const [city, setCity] = useState(user?.city || '');
  const [saving, setSaving] = useState(false);

  // Change password
  const [changingPw, setChangingPw] = useState(false);
  const [currentPw, setCurrentPw] = useState('');
  const [newPw, setNewPw] = useState('');
  const [confirmPw, setConfirmPw] = useState('');
  const [savingPw, setSavingPw] = useState(false);

  const handleSaveProfile = async () => {
    if (!firstName.trim() || !lastName.trim()) {
      Alert.alert(t('error'), t('nameRequired'));
      return;
    }
    setSaving(true);
    try {
      await userService.updateProfile(user.id, {
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        city: city.trim() || null,
      });
      await updateUser({ ...user, firstName: firstName.trim(), lastName: lastName.trim(), city: city.trim() || null });
      setEditing(false);
      Alert.alert(t('success'), t('profileUpdated'));
    } catch (err) {
      Alert.alert(t('error'), t('failedToUpdate'));
    } finally {
      setSaving(false);
    }
  };

  const handleChangePassword = async () => {
    if (newPw.length < 8) {
      Alert.alert(t('error'), t('passwordMinLength'));
      return;
    }
    if (newPw !== confirmPw) {
      Alert.alert(t('error'), t('passwordsDontMatch'));
      return;
    }
    setSavingPw(true);
    try {
      await userService.changePassword(user.id, currentPw, newPw);
      setChangingPw(false);
      setCurrentPw('');
      setNewPw('');
      setConfirmPw('');
      Alert.alert(t('success'), t('passwordChanged'));
    } catch (err) {
      Alert.alert(t('error'), err.response?.status === 400 ? t('incorrectPassword') : t('failedToChangePassword'));
    } finally {
      setSavingPw(false);
    }
  };

  const handleDeleteAccount = () => {
    Alert.alert(
      t('deleteAccount'),
      t('deleteAccountWarning'),
      [
        { text: t('cancel'), style: 'cancel' },
        {
          text: t('deleteForever'),
          style: 'destructive',
          onPress: async () => {
            try {
              await userService.deleteAccount(user.id);
              await logout();
              router.replace('/login');
            } catch (err) {
              Alert.alert(t('error'), t('failedToDeleteAccount'));
            }
          },
        },
      ]
    );
  };

  const handleLogout = () => {
    Alert.alert(t('logout'), t('logoutConfirm'), [
      { text: t('cancel'), style: 'cancel' },
      { text: t('logout'), style: 'destructive', onPress: async () => { await logout(); router.replace('/login'); } },
    ]);
  };

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container}>
      <View style={styles.avatar}>
        <Ionicons name="person" size={48} color="#5B4FE8" />
      </View>
      <Text style={styles.name}>{user?.firstName} {user?.lastName}</Text>
      <Text style={styles.email}>{user?.email}</Text>
      {user?.city && <Text style={styles.city}>{user.city}</Text>}

      {/* Language Selector */}
      <View style={styles.langSection}>
        <Text style={styles.langLabel}>{t('language')}</Text>
        <View style={styles.langRow}>
          <TouchableOpacity
            style={[styles.langBtn, language === 'en' && styles.langBtnActive]}
            onPress={() => setLanguage('en')}
          >
            <Text style={[styles.langBtnText, language === 'en' && styles.langBtnTextActive]}>
              {t('languageEnglish')}
            </Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.langBtn, language === 'hr' && styles.langBtnActive]}
            onPress={() => setLanguage('hr')}
          >
            <Text style={[styles.langBtnText, language === 'hr' && styles.langBtnTextActive]}>
              {t('languageCroatian')}
            </Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Edit Profile */}
      <View style={styles.section}>
        <TouchableOpacity style={styles.row} onPress={() => { setEditing(!editing); setChangingPw(false); }}>
          <Ionicons name="create-outline" size={22} color="#333" />
          <Text style={styles.rowText}>{t('editProfile')}</Text>
          <Ionicons name={editing ? 'chevron-up' : 'chevron-forward'} size={20} color="#ccc" />
        </TouchableOpacity>

        {editing && (
          <View style={styles.formSection}>
            <Text style={styles.label}>{t('firstName')}</Text>
            <TextInput style={styles.input} value={firstName} onChangeText={setFirstName} placeholder={t('firstName')} placeholderTextColor="#999" />

            <Text style={styles.label}>{t('lastName')}</Text>
            <TextInput style={styles.input} value={lastName} onChangeText={setLastName} placeholder={t('lastName')} placeholderTextColor="#999" />

            <Text style={styles.label}>{t('cityOptional')}</Text>
            <TextInput style={styles.input} value={city} onChangeText={setCity} placeholder={t('cityPlaceholder')} placeholderTextColor="#999" />

            <TouchableOpacity
              style={[styles.saveBtn, saving && { opacity: 0.6 }]}
              onPress={handleSaveProfile}
              disabled={saving}
            >
              <Text style={styles.saveBtnText}>{saving ? t('saving') : t('saveChanges')}</Text>
            </TouchableOpacity>
          </View>
        )}

        {/* Change Password */}
        <TouchableOpacity style={styles.row} onPress={() => { setChangingPw(!changingPw); setEditing(false); }}>
          <Ionicons name="lock-closed-outline" size={22} color="#333" />
          <Text style={styles.rowText}>{t('changePassword')}</Text>
          <Ionicons name={changingPw ? 'chevron-up' : 'chevron-forward'} size={20} color="#ccc" />
        </TouchableOpacity>

        {changingPw && (
          <View style={styles.formSection}>
            <Text style={styles.label}>{t('currentPassword')}</Text>
            <TextInput style={styles.input} value={currentPw} onChangeText={setCurrentPw} secureTextEntry placeholder={t('currentPasswordPlaceholder')} placeholderTextColor="#999" />

            <Text style={styles.label}>{t('newPassword')}</Text>
            <TextInput style={styles.input} value={newPw} onChangeText={setNewPw} secureTextEntry placeholder={t('newPasswordPlaceholder')} placeholderTextColor="#999" />

            <Text style={styles.label}>{t('confirmPassword')}</Text>
            <TextInput style={styles.input} value={confirmPw} onChangeText={setConfirmPw} secureTextEntry placeholder={t('confirmPasswordPlaceholder')} placeholderTextColor="#999" />

            <TouchableOpacity
              style={[styles.saveBtn, savingPw && { opacity: 0.6 }]}
              onPress={handleChangePassword}
              disabled={savingPw}
            >
              <Text style={styles.saveBtnText}>{savingPw ? t('changing') : t('changePassword')}</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>

      {/* Logout */}
      <TouchableOpacity style={styles.logoutBtn} onPress={handleLogout}>
        <Ionicons name="log-out-outline" size={22} color="#dc3545" />
        <Text style={styles.logoutText}>{t('logout')}</Text>
      </TouchableOpacity>

      {/* Delete Account */}
      <TouchableOpacity style={styles.deleteBtn} onPress={handleDeleteAccount}>
        <Ionicons name="trash-outline" size={18} color="#dc3545" />
        <Text style={styles.deleteText}>{t('deleteAccount')}</Text>
      </TouchableOpacity>

      <View style={{ height: 40 }} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: '#f5f5f5' },
  container: { padding: 20, alignItems: 'center' },
  avatar: { width: 90, height: 90, borderRadius: 45, backgroundColor: '#ECEAFB', alignItems: 'center', justifyContent: 'center', marginTop: 10, marginBottom: 12 },
  name: { fontSize: 22, fontWeight: '700', color: '#333' },
  email: { fontSize: 14, color: '#888', marginTop: 2 },
  city: { fontSize: 14, color: '#5B4FE8', fontWeight: '500', marginTop: 4 },

  langSection: { width: '100%', marginTop: 20 },
  langLabel: { fontSize: 14, fontWeight: '600', color: '#666', marginBottom: 8 },
  langRow: { flexDirection: 'row', gap: 10 },
  langBtn: { flex: 1, paddingVertical: 12, borderRadius: 10, backgroundColor: '#fff', alignItems: 'center', borderWidth: 2, borderColor: '#eee' },
  langBtnActive: { backgroundColor: '#5B4FE8', borderColor: '#5B4FE8' },
  langBtnText: { fontSize: 15, fontWeight: '600', color: '#333' },
  langBtnTextActive: { color: '#fff' },

  section: { width: '100%', backgroundColor: '#fff', borderRadius: 12, marginTop: 16, overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', padding: 16, borderBottomWidth: 1, borderBottomColor: '#f0f0f0', gap: 12 },
  rowText: { flex: 1, fontSize: 16, color: '#333' },

  formSection: { padding: 16, backgroundColor: '#fafafa', borderBottomWidth: 1, borderBottomColor: '#f0f0f0' },
  label: { fontSize: 13, color: '#666', fontWeight: '600', marginBottom: 4, marginTop: 10 },
  input: { borderWidth: 1, borderColor: '#ddd', borderRadius: 8, padding: 12, fontSize: 15, backgroundColor: '#fff', color: '#333' },
  saveBtn: { backgroundColor: '#5B4FE8', padding: 14, borderRadius: 10, alignItems: 'center', marginTop: 16 },
  saveBtnText: { color: '#fff', fontSize: 16, fontWeight: '700' },

  logoutBtn: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 28, padding: 14, backgroundColor: '#fff', borderRadius: 12, width: '100%', justifyContent: 'center', borderWidth: 1, borderColor: '#f5c6cb' },
  logoutText: { fontSize: 16, color: '#dc3545', fontWeight: '600' },

  deleteBtn: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 16, padding: 10, justifyContent: 'center' },
  deleteText: { fontSize: 13, color: '#dc3545' },
});
