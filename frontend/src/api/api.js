import client from './client';

// --- Аутентификация ---
export const apiLogin = (email, password) =>
  client.post('/auth/login', { email, password });
export const apiRegister = (data) =>
  client.post('/auth/register', data);

// --- Пользователь ---
export const apiGetMe = () => client.get('/users/me');
export const apiGetMyImages = () => client.get('/users/me/images');

// --- Пользователи (ADMIN) ---
export const apiGetUsers = (params = {}) => client.get('/users', { params });
export const apiGetUser = (id) => client.get(`/users/${id}`);
export const apiCreateUser = (data) => client.post('/users', data);
export const apiUpdateUser = (id, data) => client.put(`/users/${id}`, data);
export const apiDeleteUser = (id) => client.delete(`/users/${id}`);

// Списки компонентов, образов и пользователей постраничные:
// params = { page, size, search, ...фильтры } → { content, page, size, totalElements, totalPages }.

// --- Компоненты ---
export const apiGetComponents = (params = {}) =>
  client.get('/components', { params });
export const apiGetComponent = (id) => client.get(`/components/${id}`);
export const apiCreateComponent = (data) => client.post('/components', data);
export const apiUpdateComponent = (id, data) => client.put(`/components/${id}`, data);
export const apiDeleteComponent = (id) => client.delete(`/components/${id}`);
export const apiGetComponentTypes = () => client.get('/component-types');

// --- Образы ---
export const apiGetImages = (params = {}) => client.get('/images', { params });
export const apiGetImage = (id) => client.get(`/images/${id}`);
export const apiBuildImage = (data) => client.post('/images/build', data);
export const apiUpdateImage = (id, data) => client.put(`/images/${id}`, data);
export const apiDeleteImage = (id) => client.delete(`/images/${id}`);
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
