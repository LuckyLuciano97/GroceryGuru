import { createContext, useContext, useState, useEffect } from 'react';
import { getUser, getToken, saveAuth, clearAuth } from './auth';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getUser().then((u) => {
      setUser(u);
      setLoading(false);
    });
  }, []);

  const login = async (token, userData) => {
    await saveAuth(token, userData);
    setUser(userData);
  };

  const logout = async () => {
    await clearAuth();
    setUser(null);
  };

  const updateUser = async (updated) => {
    const token = await getToken();
    await saveAuth(token, updated);
    setUser(updated);
  };

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, updateUser }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
