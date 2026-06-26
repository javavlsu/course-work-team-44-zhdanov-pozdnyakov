import client from './client';

// --- Аутентификация ---
export const apiLogin = (email, password) =>
  client.post('/auth/login', { email, password });
export const apiRegister = (data) =>
  client.post('/auth/register', data);

// --- Пользователь ---
export const apiGetMe = () => client.get('/users/me');
export const apiGetMyImages = () => client.get('/users/me/images');

// --- Компоненты ---
export const apiGetComponents = (params = {}) =>
  client.get('/components', { params });
export const apiGetComponent = (id) => client.get(`/components/${id}`);
export const apiCreateComponent = (data) => client.post('/components', data);
export const apiUpdateComponent = (id, data) => client.put(`/components/${id}`, data);
export const apiDeleteComponent = (id) => client.delete(`/components/${id}`);
export const apiGetComponentTypes = () => client.get('/component-types');

// --- Образы ---
export const apiGetImages = () => client.get('/images');
export const apiGetImage = (id) => client.get(`/images/${id}`);
export const apiBuildImage = (data) => client.post('/images/build', data);
export const apiDownloadUrl = (id) =>
  `${client.defaults.baseURL}/images/${id}/download`;

// Извлечение читаемого сообщения об ошибке из ответа бэкенда.
export const extractError = (error, fallback = 'Произошла ошибка') => {
  const data = error.response?.data;
  if (!data) return error.message || fallback;
  if (typeof data === 'string') return data;
  if (data.error) return data.error;
  if (typeof data === 'object') return Object.values(data).join('. ');
  return fallback;
};
