import React, { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    const res = await login(email, password);
    setBusy(false);
    if (res.success) {
      navigate(location.state?.from?.pathname || '/library/images', { replace: true });
    } else {
      setError(res.message);
    }
  };

  return (
    <div className="auth">
      <form className="auth__card" onSubmit={submit}>
        <h1 className="auth__title">Вход</h1>
        {error && <div className="alert alert--error">{error}</div>}
        <label className="field">
          <span>Email</span>
          <input type="email" value={email} required
                 onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" />
        </label>
        <label className="field">
          <span>Пароль</span>
          <input type="password" value={password} required
                 onChange={(e) => setPassword(e.target.value)} placeholder="••••••••" />
        </label>
        <button className="btn btn--primary btn--block" disabled={busy}>
          {busy ? 'Вход…' : 'Войти'}
        </button>
        <p className="auth__switch">
          Нет аккаунта? <Link to="/register">Зарегистрироваться</Link>
        </p>
      </form>
    </div>
  );
}
