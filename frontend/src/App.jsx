import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Header from './components/Header';
import ProtectedRoute from './components/ProtectedRoute';
import Login from './pages/Login';
import Register from './pages/Register';
import ImageLibrary from './pages/ImageLibrary';
import ComponentLibrary from './pages/ComponentLibrary';
import ImageEditor from './pages/ImageEditor';
import ComponentEditor from './pages/ComponentEditor';
import Account from './pages/Account';

export default function App() {
  const { loading } = useAuth();

  if (loading) {
    return <div className="page-loading">Загрузка…</div>;
  }

  return (
    <>
      <Header />
      <main className="app-main">
        <Routes>
          <Route path="/" element={<Navigate to="/library/images" replace />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/library/images" element={<ImageLibrary />} />
          <Route path="/library/components" element={<ComponentLibrary />} />
          <Route
            path="/editor/image"
            element={
              <ProtectedRoute>
                <ImageEditor />
              </ProtectedRoute>
            }
          />
          <Route
            path="/editor/component"
            element={
              <ProtectedRoute adminOnly>
                <ComponentEditor />
              </ProtectedRoute>
            }
          />
          <Route
            path="/account"
            element={
              <ProtectedRoute>
                <Account />
              </ProtectedRoute>
            }
          />
          <Route path="*" element={<Navigate to="/library/images" replace />} />
        </Routes>
      </main>
    </>
  );
}
