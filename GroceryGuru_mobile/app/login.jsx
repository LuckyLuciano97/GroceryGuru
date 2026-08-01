import { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, KeyboardAvoidingView, Platform } from 'react-native';
import { useRouter } from 'expo-router';
import { authService, formatApiError } from '../services/api';
import { useAuth } from '../services/AuthContext';
import { useI18n } from '../services/i18n';

// Shared read-only-ish account so the deployed demo can be tried without signing up.
const DEMO_EMAIL = 'demo@gg.test';
const DEMO_PASSWORD = 'demo1234';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const router = useRouter();
  const { t } = useI18n();

  const signIn = async (mail, pass) => {
    setError('');
    setLoading(true);
    try {
      const res = await authService.login(mail, pass);
      await login(res.data.token, res.data.user);
      router.replace('/(tabs)/lists');
    } catch (err) {
      setError(formatApiError(err, t('invalidCredentials')));
    } finally {
      setLoading(false);
    }
  };

  const handleDemo = () => signIn(DEMO_EMAIL, DEMO_PASSWORD);

  const handleLogin = async () => {
    if (!email.trim() || !password) {
      setError(t('allFieldsRequired'));
      return;
    }
    setError('');
    setLoading(true);
    try {
      const res = await authService.login(email.trim(), password);
      await login(res.data.token, res.data.user);
      router.replace('/(tabs)/lists');
    } catch (err) {
      setError(formatApiError(err, t('invalidCredentials')));
    } finally {
      setLoading(false);
    }
  };

  return (
    <KeyboardAvoidingView style={styles.container} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <View style={styles.inner}>
        <Text style={styles.title}>{t('appName')}</Text>
        <Text style={styles.subtitle}>{t('appSubtitle')}</Text>

        {error ? <Text style={styles.error}>{error}</Text> : null}

        <TextInput
          style={styles.input}
          placeholder={t('email')}
          value={email}
          onChangeText={setEmail}
          keyboardType="email-address"
          autoCapitalize="none"
          placeholderTextColor="#999"
        />
        <TextInput
          style={styles.input}
          placeholder={t('password')}
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          placeholderTextColor="#999"
        />

        <TouchableOpacity style={[styles.btn, loading && styles.btnDisabled]} onPress={handleLogin} disabled={loading}>
          <Text style={styles.btnText}>{loading ? t('loggingIn') : t('login')}</Text>
        </TouchableOpacity>

        <TouchableOpacity style={[styles.demoBtn, loading && styles.btnDisabled]} onPress={handleDemo} disabled={loading}>
          <Text style={styles.demoBtnText}>{t('tryDemo')}</Text>
        </TouchableOpacity>
        <Text style={styles.demoHint}>{t('tryDemoHint')}</Text>

        <TouchableOpacity onPress={() => router.push('/register')}>
          <Text style={styles.link}>{t('noAccount')} <Text style={styles.linkBold}>{t('register')}</Text></Text>
        </TouchableOpacity>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  demoBtn: { borderWidth: 2, borderColor: '#5B4FE8', padding: 14, borderRadius: 10, alignItems: 'center', marginTop: 12 },
  demoBtnText: { color: '#5B4FE8', fontSize: 16, fontWeight: '700' },
  demoHint: { textAlign: 'center', marginTop: 8, color: '#888', fontSize: 12 },
  container: { flex: 1, backgroundColor: '#fff' },
  inner: { flex: 1, justifyContent: 'center', padding: 24 },
  title: { fontSize: 32, fontWeight: 'bold', color: '#1E2A78', textAlign: 'center', marginBottom: 4 },
  subtitle: { fontSize: 15, color: '#888', textAlign: 'center', marginBottom: 32 },
  error: { backgroundColor: '#f8d7da', color: '#721c24', padding: 12, borderRadius: 8, marginBottom: 16, fontSize: 14 },
  input: { borderWidth: 2, borderColor: '#ddd', borderRadius: 10, padding: 14, fontSize: 16, marginBottom: 14, color: '#333' },
  btn: { backgroundColor: '#5B4FE8', padding: 16, borderRadius: 10, alignItems: 'center', marginTop: 4 },
  btnDisabled: { backgroundColor: '#6c757d' },
  btnText: { color: '#fff', fontSize: 17, fontWeight: '600' },
  link: { textAlign: 'center', marginTop: 20, color: '#666', fontSize: 14 },
  linkBold: { color: '#5B4FE8', fontWeight: '600' },
});
