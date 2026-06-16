import { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, KeyboardAvoidingView, Platform, ScrollView } from 'react-native';
import { useRouter } from 'expo-router';
import { authService } from '../services/api';
import { useAuth } from '../services/AuthContext';
import { useI18n } from '../services/i18n';

export default function Register() {
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const router = useRouter();
  const { t } = useI18n();

  const handleRegister = async () => {
    if (!firstName.trim() || !email.trim() || !password) return;
    setError('');
    setLoading(true);
    try {
      const res = await authService.register({
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        email: email.trim(),
        password,
      });
      await login(res.data.token, res.data.user);
      router.replace('/(tabs)/lists');
    } catch (err) {
      const msg = err.response?.data?.message || err.response?.data?.email || t('registrationFailed');
      setError(typeof msg === 'string' ? msg : JSON.stringify(msg));
    } finally {
      setLoading(false);
    }
  };

  return (
    <KeyboardAvoidingView style={styles.container} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={styles.inner} keyboardShouldPersistTaps="handled">
        <Text style={styles.title}>{t('createAccount')}</Text>

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <TextInput style={styles.input} placeholder={t('firstName')} value={firstName} onChangeText={setFirstName} placeholderTextColor="#999" />
        <TextInput style={styles.input} placeholder={t('lastName')} value={lastName} onChangeText={setLastName} placeholderTextColor="#999" />
        <TextInput style={styles.input} placeholder={t('email')} value={email} onChangeText={setEmail} keyboardType="email-address" autoCapitalize="none" placeholderTextColor="#999" />
        <TextInput style={styles.input} placeholder={t('passwordMin')} value={password} onChangeText={setPassword} secureTextEntry placeholderTextColor="#999" />

        <TouchableOpacity style={[styles.btn, loading && styles.btnDisabled]} onPress={handleRegister} disabled={loading}>
          <Text style={styles.btnText}>{loading ? t('creating') : t('register')}</Text>
        </TouchableOpacity>

        <TouchableOpacity onPress={() => router.back()}>
          <Text style={styles.link}>{t('haveAccount')} <Text style={styles.linkBold}>{t('login')}</Text></Text>
        </TouchableOpacity>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#fff' },
  inner: { flexGrow: 1, justifyContent: 'center', padding: 24 },
  title: { fontSize: 28, fontWeight: 'bold', color: '#1E2A78', textAlign: 'center', marginBottom: 28 },
  error: { backgroundColor: '#f8d7da', color: '#721c24', padding: 12, borderRadius: 8, marginBottom: 16, fontSize: 14 },
  input: { borderWidth: 2, borderColor: '#ddd', borderRadius: 10, padding: 14, fontSize: 16, marginBottom: 14, color: '#333' },
  btn: { backgroundColor: '#5B4FE8', padding: 16, borderRadius: 10, alignItems: 'center', marginTop: 4 },
  btnDisabled: { backgroundColor: '#6c757d' },
  btnText: { color: '#fff', fontSize: 17, fontWeight: '600' },
  link: { textAlign: 'center', marginTop: 20, color: '#666', fontSize: 14 },
  linkBold: { color: '#5B4FE8', fontWeight: '600' },
});
