import React, { createContext, useState, useContext, useEffect, useCallback } from 'react';
import { apiLogin, apiRegister, apiGetMe, extractError } from '../api/api';

const AuthContext = createContext(null);

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(() => localStorage.getItem('token'));
  const [loading, setLoading] = useState(true);

  const loadProfile = useCallback(async () => {
    try {
      const { data } = await apiGetMe();
      setUser(data);
    } catch {
      setUser(null);
    }
  }, []);

  useEffect(() => {
    (async () => {
      if (token) {
        localStorage.setItem('token', token);
        await loadProfile();
      } else {
        localStorage.removeItem('token');
        setUser(null);
      }
      setLoading(false);
    })();
  }, [token, loadProfile]);

  const login = async (email, password) => {
    try {
      const { data } = await apiLogin(email, password);
      setToken(data.token);
      return { success: true };
    } catch (error) {
      return { success: false, message: extractError(error, 'Не удалось войти') };
    }
  };

  const register = async (userData) => {
    try {
      const { data } = await apiRegister(userData);
      setToken(data.token);
      return { success: true };
    } catch (error) {
      return { success: false, message: extractError(error, 'Не удалось зарегистрироваться') };
    }
  };

  const logout = () => {
    setToken(null);
    setUser(null);
    localStorage.removeItem('token');
  };

  const isAdmin = !!user?.roles?.includes('ADMIN');

  return (
    <AuthContext.Provider
      value={{ user, token, isAuthenticated: !!token, isAdmin, login, register, logout, loading }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
