import React, { useState } from 'react';
import {
  apiGetUsers, apiCreateUser, apiUpdateUser, apiDeleteUser, extractError,
} from '../api/api';
import { useAuth } from '../context/AuthContext';
import Pagination from '../components/Pagination';
import usePagedList from '../hooks/usePagedList';
import { ROLES } from '../constants';

const EMPTY = { name: '', surname: '', email: '', password: '', roles: ['USER'] };

export default function UserManagement() {
  const { user: me } = useAuth();
  const [form, setForm] = useState(EMPTY);
  const [editing, setEditing] = useState(null); // id редактируемого пользователя
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);

  const [search, setSearch] = useState('');
  const [role, setRole] = useState('');
  const list = usePagedList(apiGetUsers, { search, role }, { size: 10 });

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const toggleRole = (r) => {
    setForm((f) => ({
      ...f,
      roles: f.roles.includes(r) ? f.roles.filter((x) => x !== r) : [...f.roles, r],
    }));
  };

  const startEdit = (u) => {
    setError(''); setOk('');
    setEditing(u.id);
    setForm({ name: u.name, surname: u.surname || '', email: u.email, password: '', roles: [...(u.roles || [])] });
  };

  const cancelEdit = () => {
    setEditing(null);
    setForm(EMPTY);
  };

  const submit = async (e) => {
    e.preventDefault();
    setError(''); setOk('');
    if (form.roles.length === 0) return setError('Выберите хотя бы одну роль');
    setBusy(true);
    // Пустой пароль при редактировании — оставить прежний.
    const body = { ...form, password: form.password || undefined };
    try {
      if (editing) {
        await apiUpdateUser(editing, body);
        setOk(`Пользователь ${form.email} сохранён`);
      } else {
        await apiCreateUser(body);
        setOk(`Пользователь ${form.email} создан`);
      }
      cancelEdit();
      await list.reload();
    } catch (err) {
      setError(extractError(err, 'Не удалось сохранить пользователя'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (u) => {
    if (!window.confirm(`Удалить пользователя ${u.email}?`)) return;
    setError(''); setOk('');
    try {
      await apiDeleteUser(u.id);
      if (editing === u.id) cancelEdit();
      await list.reload();
    } catch (err) {
      setError(extractError(err, 'Не удалось удалить пользователя'));
    }
  };

  return (
    <div className="page editor">
      <h1 className="page__title">Пользователи</h1>
      <p className="page__subtitle">Доступно администраторам</p>
      {error && <div className="alert alert--error">{error}</div>}
      {ok && <div className="alert alert--ok">{ok}</div>}

      <div className="editor__layout">
        <form className="editor__panel" onSubmit={submit}>
          <h3 style={{ marginBottom: 12 }}>{editing ? `Редактирование #${editing}` : 'Новый пользователь'}</h3>
          <div className="field-row">
            <label className="field">
              <span>Имя</span>
              <input className="input" name="name" value={form.name} required onChange={onChange} />
            </label>
            <label className="field">
              <span>Фамилия</span>
              <input className="input" name="surname" value={form.surname} onChange={onChange} />
            </label>
          </div>
          <label className="field">
            <span>Email</span>
            <input className="input" type="email" name="email" value={form.email} required onChange={onChange} />
          </label>
          <label className="field">
            <span>{editing ? 'Новый пароль (пусто — не менять)' : 'Пароль'}</span>
            <input className="input" type="password" name="password" value={form.password}
                   required={!editing} onChange={onChange} autoComplete="new-password" />
          </label>
          <p className="hint">
            Пароль: ≥8 символов, минимум одна цифра, строчная и заглавная буквы и спецсимвол (@#$%^&+=!).
          </p>
          <div className="field" style={{ marginTop: 12 }}>
            <span>Роли</span>
            <div className="toolbar" style={{ marginBottom: 0 }}>
              {ROLES.map((r) => (
                <label key={r} className="dep-check">
                  <input type="checkbox" checked={form.roles.includes(r)} onChange={() => toggleRole(r)} />
                  {r}
                </label>
              ))}
            </div>
          </div>

          <button className="btn btn--primary btn--block" disabled={busy}>
            {busy ? 'Сохранение…' : editing ? 'Сохранить' : 'Создать пользователя'}
          </button>
          {editing && (
            <button type="button" className="btn btn--ghost btn--block" style={{ marginTop: 8 }} onClick={cancelEdit}>
              Отмена
            </button>
          )}
        </form>

        <aside className="editor__catalog">
          <h3>Все пользователи ({list.totalElements})</h3>
          <div className="toolbar">
            <input className="input input--search" placeholder="Имя или email…"
                   value={search} onChange={(e) => setSearch(e.target.value)} />
            <select className="input" value={role} onChange={(e) => setRole(e.target.value)}>
              <option value="">Все роли</option>
              {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
          </div>
          {list.error && <div className="alert alert--error">{list.error}</div>}
          <ul className="catalog-list">
            {list.items.map((u) => (
              <li key={u.id} className={`catalog-item${u.id === editing ? ' catalog-item--active' : ''}`}>
                <div>
                  <div className="catalog-item__name">{u.name} {u.surname || ''}</div>
                  <div className="catalog-item__type">{u.email} · {(u.roles || []).join(', ')}</div>
                </div>
                <div className="catalog-item__actions">
                  <button className="icon-btn icon-btn--edit" title="Изменить" onClick={() => startEdit(u)}>✎</button>
                  <button className="icon-btn" title={u.email === me?.email ? 'Нельзя удалить себя' : 'Удалить'}
                          disabled={u.email === me?.email} onClick={() => remove(u)}>✕</button>
                </div>
              </li>
            ))}
            {!list.loading && list.items.length === 0 && <li className="hint">Никого не найдено</li>}
          </ul>
          <Pagination page={list.page} totalPages={list.totalPages}
                      totalElements={list.totalElements} onChange={list.setPage} />
        </aside>
      </div>
    </div>
  );
}
