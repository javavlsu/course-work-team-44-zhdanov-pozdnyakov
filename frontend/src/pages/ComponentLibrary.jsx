import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiGetComponents, apiGetComponentTypes, apiDeleteComponent, extractError } from '../api/api';
import { useAuth } from '../context/AuthContext';
import ComponentCard from '../components/ComponentCard';
import Pagination from '../components/Pagination';
import usePagedList from '../hooks/usePagedList';
import { PRICE_SORTS } from '../constants';

export default function ComponentLibrary() {
  const [types, setTypes] = useState([]);
  const [search, setSearch] = useState('');
  const [typeId, setTypeId] = useState('');
  const [actionError, setActionError] = useState('');
  const { isAdmin } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    apiGetComponentTypes().then(({ data }) => setTypes(data)).catch(() => {});
  }, []);

  const [sort, setSort] = useState('');
  const list = usePagedList(apiGetComponents, { search, type: typeId, sort }, { size: 12 });
  const error = actionError || list.error;

  const remove = async (c) => {
    if (!window.confirm(`Удалить компонент «${c.name}»?`)) return;
    setActionError('');
    try {
      await apiDeleteComponent(c.id);
      await list.reload();
    } catch (e) {
      setActionError(extractError(e, 'Не удалось удалить компонент'));
    }
  };

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Библиотека компонентов</h1>
          <p className="page__subtitle">Пакеты и библиотеки с автоматическим разрешением зависимостей</p>
        </div>
        {isAdmin && (
          <button className="btn btn--primary" onClick={() => navigate('/editor/component')}>
            + Компонент
          </button>
        )}
      </div>

      <div className="toolbar">
        <input
          className="input input--search"
          placeholder="Поиск компонентов…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select className="input" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
          <option value="">Все типы</option>
          {types.map((t) => (
            <option key={t.id} value={t.id}>{t.type}</option>
          ))}
        </select>
        <select className="input" value={sort} onChange={(e) => setSort(e.target.value)}>
          <option value="">По названию</option>
          {PRICE_SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
      </div>

      {error && <div className="alert alert--error">{error}</div>}
      {list.loading ? (
        <div className="empty">Загрузка…</div>
      ) : list.items.length === 0 ? (
        <div className="empty">Ничего не найдено</div>
      ) : (
        <div className="grid">
          {list.items.map((c) => (
            <ComponentCard
              key={c.id}
              component={c}
              onEdit={isAdmin ? () => navigate(`/editor/component?id=${c.id}`) : undefined}
              onDelete={isAdmin ? remove : undefined}
            />
          ))}
        </div>
      )}
      <Pagination page={list.page} totalPages={list.totalPages}
                  totalElements={list.totalElements} onChange={list.setPage} />
    </div>
  );
}
