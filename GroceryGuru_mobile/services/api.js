import axios from 'axios';
import { Platform } from 'react-native';
import { getToken, clearAuth } from './auth';

// Deployed backend on Railway (public HTTPS) — reachable over the internet, so
// the phone needs no LAN/VPN/ngrok. Web dev still uses a local server if running.
const API_URL = 'https://groceryguru-production-1b67.up.railway.app';
const API_BASE_URL = Platform.select({
  web: `${API_URL}/api`,
  default: `${API_URL}/api`,
});

const api = axios.create({ baseURL: API_BASE_URL, timeout: 10000 });

api.interceptors.request.use(async (config) => {
  const token = await getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

api.interceptors.response.use(
  (res) => res,
  async (err) => {
    if (err.response?.status === 401 || err.response?.status === 403) {
      await clearAuth();
    }
    return Promise.reject(err);
  }
);

export const authService = {
  login: (email, password) => api.post('/auth/login', { email, password }),
  register: (data) => api.post('/auth/register', data),
};

export const shoppingListService = {
  getForUser: (userId) => api.get(`/shopping-lists/user/${userId}`),
  create: (name, userId) => api.post('/shopping-lists', { name, userId }),
  deleteList: (listId) => api.delete(`/shopping-lists/${listId}`),
  addGenericItem: (listId, searchTerm, quantity) =>
    api.post(`/shopping-lists/${listId}/generic-items`, { searchTerm, quantity }),
  addItem: (listId, productId, quantity) =>
    api.post(`/shopping-lists/${listId}/items`, { productId, quantity }),
  removeItem: (itemId) => api.delete(`/shopping-lists/items/${itemId}`),
  updateItemQuantity: (itemId, quantity) =>
    api.put(`/shopping-lists/items/${itemId}`, { quantity }),
  optimize: (listId, city) => {
    const params = city ? `?city=${encodeURIComponent(city)}` : '';
    return api.get(`/shopping-lists/${listId}/optimize${params}`);
  },
  getAlternatives: (chain, searchTerm, productId, city) => {
    let params = `?chain=${encodeURIComponent(chain)}&searchTerm=${encodeURIComponent(searchTerm)}`;
    if (productId) params += `&productId=${productId}`;
    if (city) params += `&city=${encodeURIComponent(city)}`;
    return api.get(`/shopping-lists/alternatives${params}`);
  },
  shareList: (listId, email, userId) =>
    api.post(`/shopping-lists/${listId}/share`, { email, userId }),
  getMembers: (listId) => api.get(`/shopping-lists/${listId}/members`),
  removeMember: (listId, memberId, userId) =>
    api.delete(`/shopping-lists/${listId}/members/${memberId}?userId=${userId}`),
};

export const productService = {
  search: (query, page = 0, size = 8) =>
    api.get(`/products/search?name=${encodeURIComponent(query)}&page=${page}&size=${size}`),
};

export const loyaltyCardService = {
  list: () => api.get('/loyalty-cards'),
  add: (chain, number, codeType = 'BARCODE') =>
    api.post('/loyalty-cards', { chain, number, codeType }),
  remove: (id) => api.delete(`/loyalty-cards/${id}`),
};

export const userService = {
  updateProfile: (id, data) => api.put(`/users/${id}/profile`, data),
  changePassword: (id, currentPassword, newPassword) =>
    api.put(`/users/${id}/password`, { currentPassword, newPassword }),
  deleteAccount: (id) => api.delete(`/users/${id}`),
};

export const storeService = {
  getNearbyStores: (latitude, longitude, radiusKm = 25, city = null) => {
    let params = `?latitude=${latitude}&longitude=${longitude}&radiusKm=${radiusKm}`;
    if (city) params += `&city=${encodeURIComponent(city)}`;
    return api.get(`/stores/nearby${params}`);
  },
  searchByCity: (city) => api.get(`/stores/search?city=${encodeURIComponent(city)}`),
  getCities: (query) => api.get(`/stores/cities?query=${encodeURIComponent(query)}`),
  getById: (id) => api.get(`/stores/${id}`),
};

export default api;
