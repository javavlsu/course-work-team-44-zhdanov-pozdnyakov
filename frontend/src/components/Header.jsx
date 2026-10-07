import React from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Header() {
  const { isAuthenticated, isAdmin, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <header className="header">
      <div className="header__brand" onClick={() => navigate('/library/images')}>
        <span className="header__logo">⬡</span>
        <span>Luckfox Image Builder</span>
      </div>

      <nav className="header__nav">
        <NavLink to="/library/images" className="nav-link">Образы</NavLink>
        <NavLink to="/library/components" className="nav-link">Компоненты</NavLink>
        {isAuthenticated && (
          <NavLink to="/editor/image" className="nav-link">Создать образ</NavLink>
        )}
        {isAdmin && (
          <>
            <NavLink to="/editor/component" className="nav-link">+ Компонент</NavLink>
            <NavLink to="/admin/users" className="nav-link">Пользователи</NavLink>
          </>
        )}
      </nav>

      <div className="header__actions">
        {isAuthenticated ? (
          <>
            <NavLink to="/account" className="nav-link">Аккаунт</NavLink>
            <button className="btn btn--ghost" onClick={handleLogout}>Выйти</button>
          </>
        ) : (
          <>
            <NavLink to="/login" className="btn btn--ghost">Вход</NavLink>
            <NavLink to="/register" className="btn btn--primary">Регистрация</NavLink>
          </>
        )}
      </div>
    </header>
  );
}
