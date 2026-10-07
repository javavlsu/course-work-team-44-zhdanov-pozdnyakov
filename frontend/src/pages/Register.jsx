import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: '', surname: '', email: '', password: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    const res = await register(form);
    setBusy(false);
    if (res.success) {
      navigate('/library/images', { replace: true });
    } else {
      setError(res.message);
    }
  };

  return (
    <div className="auth">
      <form className="auth__card" onSubmit={submit}>
        <h1 className="auth__title">Регистрация</h1>
        {error && <div className="alert alert--error">{error}</div>}
        <div className="field-row">
          <label className="field">
            <span>Имя</span>
            <input name="name" value={form.name} required onChange={onChange} placeholder="Иван" />
          </label>
          <label className="field">
            <span>Фамилия</span>
            <input name="surname" value={form.surname} onChange={onChange} placeholder="Иванов" />
          </label>
        </div>
        <label className="field">
          <span>Email</span>
          <input type="email" name="email" value={form.email} required
                 onChange={onChange} placeholder="you@example.com" />
        </label>
        <label className="field">
          <span>Пароль</span>
          <input type="password" name="password" value={form.password} required
                 onChange={onChange} placeholder="Минимум 8 символов" />
        </label>
        <p className="hint">
          Пароль: ≥8 символов, минимум одна цифра, строчная и заглавная буквы и спецсимвол (@#$%^&+=!).
        </p>
        <button className="btn btn--primary btn--block" disabled={busy}>
          {busy ? 'Создание…' : 'Зарегистрироваться'}
        </button>
        <p className="auth__switch">
          Уже есть аккаунт? <Link to="/login">Войти</Link>
        </p>
      </form>
    </div>
  );
}
