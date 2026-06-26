import React, { useEffect, useState } from 'react';
import { apiGetComponents, apiGetComponentTypes, extractError } from '../api/api';
import ComponentCard from '../components/ComponentCard';

export default function ComponentLibrary() {
  const [components, setComponents] = useState([]);
  const [types, setTypes] = useState([]);
  const [search, setSearch] = useState('');
  const [typeId, setTypeId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    apiGetComponentTypes().then(({ data }) => setTypes(data)).catch(() => {});
  }, []);

  const load = async () => {
    setLoading(true);
    try {
      const params = {};
      if (search) params.search = search;
      if (typeId) params.type = typeId;
      const { data } = await apiGetComponents(params);
      setComponents(data);
    } catch (e) {
      setError(extractError(e, 'Не удалось загрузить компоненты'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    const t = setTimeout(load, 250); // debounce поиска
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search, typeId]);

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Библиотека компонентов</h1>
          <p className="page__subtitle">Пакеты и библиотеки с автоматическим разрешением зависимостей</p>
        </div>
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
      </div>

      {error && <div className="alert alert--error">{error}</div>}
      {loading ? (
        <div className="empty">Загрузка…</div>
      ) : components.length === 0 ? (
        <div className="empty">Ничего не найдено</div>
      ) : (
        <div className="grid">
          {components.map((c) => (
            <ComponentCard key={c.id} component={c} />
          ))}
        </div>
      )}
    </div>
  );
}
