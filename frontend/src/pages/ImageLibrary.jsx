import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiGetImages, apiDeleteImage, apiDownloadUrl, extractError } from '../api/api';
import { useAuth } from '../context/AuthContext';
import ImageCard from '../components/ImageCard';
import Pagination from '../components/Pagination';
import { LABELS } from '../components/StatusBadge';
import usePagedList from '../hooks/usePagedList';
import ComponentFilter from '../components/ComponentFilter';
import { BOARDS, PRICE_SORTS } from '../constants';

export default function ImageLibrary() {
  const [search, setSearch] = useState('');
  const [state, setState] = useState('');
  const [board, setBoard] = useState('');
  const [components, setComponents] = useState([]); // [{ id, name }]
  const [sort, setSort] = useState('');
  const [actionError, setActionError] = useState('');
  const { isAuthenticated, isAdmin } = useAuth();
  const navigate = useNavigate();

  // Автообновление статусов сборок каждые 5 с.
  const list = usePagedList(
    apiGetImages,
    { search, state, board, sort, components: components.map((c) => c.id).join(',') },
    { size: 12, pollMs: 5000 },
  );
  const error = actionError || list.error;

  const download = (image) => {
    const token = localStorage.getItem('token');
    // Скачивание защищено JWT — открываем через fetch с заголовком.
    fetch(apiDownloadUrl(image.id), { headers: { Authorization: `Bearer ${token}` } })
      .then((r) => { if (!r.ok) throw new Error('Загрузка недоступна'); return r.blob(); })
      .then((blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${image.name || 'image'}.img`;
        a.click();
        URL.revokeObjectURL(url);
      })
      .catch((e) => setActionError(e.message));
  };

  const remove = async (image) => {
    if (!window.confirm(`Удалить образ «${image.name || image.id}»?`)) return;
    setActionError('');
    try {
      await apiDeleteImage(image.id);
      await list.reload();
    } catch (e) {
      setActionError(extractError(e, 'Не удалось удалить образ'));
    }
  };

  return (
    <div className="page">
      <div className="page__head">
        <div>
          <h1 className="page__title">Библиотека образов</h1>
          <p className="page__subtitle">Готовые и собираемые образы для Luckfox Pico</p>
        </div>
        {isAuthenticated && (
          <button className="btn btn--primary" onClick={() => navigate('/editor/image')}>
            + Создать образ
          </button>
        )}
      </div>

      <div className="toolbar">
        <input className="input input--search" placeholder="Поиск образов…"
               value={search} onChange={(e) => setSearch(e.target.value)} />
        <select className="input" value={state} onChange={(e) => setState(e.target.value)}>
          <option value="">Все статусы</option>
          {Object.entries(LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <select className="input" value={board} onChange={(e) => setBoard(e.target.value)}>
          <option value="">Все платы</option>
          {BOARDS.map((b) => <option key={b} value={b}>{b}</option>)}
        </select>
        <select className="input" value={sort} onChange={(e) => setSort(e.target.value)}>
          <option value="">Сначала новые</option>
          {PRICE_SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
        </select>
        <ComponentFilter selected={components} onChange={setComponents} />
      </div>

      {error && <div className="alert alert--error">{error}</div>}
      {list.loading ? (
        <div className="empty">Загрузка…</div>
      ) : list.items.length === 0 ? (
        <div className="empty">Образов не найдено</div>
      ) : (
        <div className="grid">
          {list.items.map((img) => (
            <ImageCard
              key={img.id}
              image={img}
              onDownload={download}
              onEdit={isAdmin ? () => navigate(`/editor/image?id=${img.id}`) : undefined}
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
