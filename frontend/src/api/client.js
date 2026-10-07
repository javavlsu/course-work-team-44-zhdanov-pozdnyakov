import axios from 'axios';

// Базовый URL: в dev используется vite-прокси (/api). Можно переопределить
// через VITE_API_URL (например, http://localhost:8080/api).
const baseURL = import.meta.env.VITE_API_URL || '/api';

const client = axios.create({ baseURL });

// Подставляем JWT в каждый запрос.
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// При 401 — сбрасываем сессию и уводим на вход.
client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      if (!window.location.pathname.startsWith('/login')) {
        window.location.assign('/login');
      }
    }
    return Promise.reject(error);
  }
);

export default client;
