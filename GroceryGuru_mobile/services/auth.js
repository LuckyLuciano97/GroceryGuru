import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

const isWeb = Platform.OS === 'web';

async function getItem(key) {
  if (isWeb) return localStorage.getItem(key);
  return SecureStore.getItemAsync(key);
}

async function setItem(key, value) {
  if (isWeb) return localStorage.setItem(key, value);
  return SecureStore.setItemAsync(key, value);
}

async function deleteItem(key) {
  if (isWeb) return localStorage.removeItem(key);
  return SecureStore.deleteItemAsync(key);
}

export async function getUser() {
  const json = await getItem('user');
  return json ? JSON.parse(json) : null;
}

export async function getToken() {
  return getItem('token');
}

export async function saveAuth(token, user) {
  await setItem('token', token);
  await setItem('user', JSON.stringify(user));
}

export async function clearAuth() {
  await deleteItem('token');
  await deleteItem('user');
}
