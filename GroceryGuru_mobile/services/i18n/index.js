import { createContext, useContext, useState, useEffect } from 'react';
import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import en from './en';
import hr from './hr';

const translations = { en, hr };
const isWeb = Platform.OS === 'web';

const I18nContext = createContext(null);

async function getStoredLanguage() {
  try {
    if (isWeb) return localStorage.getItem('language') || 'en';
    return (await SecureStore.getItemAsync('language')) || 'en';
  } catch {
    return 'en';
  }
}

async function storeLanguage(lang) {
  try {
    if (isWeb) localStorage.setItem('language', lang);
    else await SecureStore.setItemAsync('language', lang);
  } catch {}
}

export function I18nProvider({ children }) {
  const [language, setLanguageState] = useState('en');
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    getStoredLanguage().then((lang) => {
      setLanguageState(lang);
      setLoaded(true);
    });
  }, []);

  const setLanguage = async (lang) => {
    setLanguageState(lang);
    await storeLanguage(lang);
  };

  const t = (key) => {
    return translations[language]?.[key] || translations.en[key] || key;
  };

  return (
    <I18nContext.Provider value={{ language, setLanguage, t, loaded }}>
      {children}
    </I18nContext.Provider>
  );
}

export function useI18n() {
  return useContext(I18nContext);
}
