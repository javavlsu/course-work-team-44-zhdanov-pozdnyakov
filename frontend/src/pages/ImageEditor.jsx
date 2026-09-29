import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  apiGetComponents, apiGetComponentTypes, apiBuildImage, apiGetImage, apiUpdateImage, extractError,
} from '../api/api';
import { useEditorStore } from '../store/editorStore';
import { useAuth } from '../context/AuthContext';
import Pagination from '../components/Pagination';
import usePagedList from '../hooks/usePagedList';
import { BOARDS, DISTROS } from '../constants';

export default function ImageEditor() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const editId = params.get('id');
  const { isAdmin } = useAuth();
  const {
    editingId, name, description, distro, board, selected,
    setField, addComponent, removeComponent, isSelected, totalCost, reset, loadImage,
  } = useEditorStore();

  const [types, setTypes] = useState([]);
  const [search, setSearch] = useState('');
  const [typeId, setTypeId] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Каталог: поиск и фильтр по типу передаются вместе, пагинация — на сервере.
  const catalog = usePagedList(apiGetComponents, { search, type: typeId }, { size: 10 });

  useEffect(() => {
    apiGetComponentTypes().then(({ data }) => setTypes(data)).catch(() => {});
  }, []);

  // Режим редактирования (?id=) — загружаем образ; при переходе к новому образу
  // сбрасываем данные, оставшиеся от редактирования.
  useEffect(() => {
    if (!editId) {
      if (useEditorStore.getState().editingId) reset();
      return;
    }
    if (!isAdmin) {
      navigate('/library/images', { replace: true });
      return;
    }
    apiGetImage(editId)
      .then(({ data }) => loadImage(data))
      .catch((e) => setError(extractError(e, 'Не удалось загрузить образ')));
  }, [editId, isAdmin, navigate, reset, loadImage]);

  const isEdit = !!editId;

  const submit = async () => {
    setError('');
    if (!name.trim()) return setError('Укажите название образа');
    if (selected.length === 0) return setError('Добавьте хотя бы один компонент');
    setBusy(true);
    try {
      const body = {
        name, description, distro, board,
        componentIds: selected.map((c) => c.id),
      };
      if (isEdit) {
        await apiUpdateImage(editingId, body);
      } else {
        await apiBuildImage(body);
      }
      reset();
      navigate('/library/images');
    } catch (e) {
      setError(extractError(e, isEdit ? 'Не удалось сохранить образ' : 'Не удалось заказать сборку'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page editor">
      <h1 className="page__title">{isEdit ? `Редактирование образа #${editId}` : 'Создать образ'}</h1>
      {isEdit && (
        <p className="page__subtitle">После сохранения образ будет пересобран с новыми параметрами.</p>
      )}
      {error && <div className="alert alert--error">{error}</div>}

      <div className="editor__layout">
        {/* Левая колонка: параметры образа и выбранные зависимости */}
        <section className="editor__panel">
          <label className="field">
            <span>
              Название <span className="hint">({name.length}/40)</span>
            </span>
            <input
              className="input"
              value={name}
              maxLength={40}
              onChange={(e) => setField('name', e.target.value)}
              placeholder="my-image"
            />
          </label>

          <div className="field-row">
            <label className="field">
              <span>Базовый дистрибутив</span>
              <select className="input" value={distro} onChange={(e) => setField('distro', e.target.value)}>
                {DISTROS.map((d) => <option key={d} value={d}>{d}</option>)}
              </select>
            </label>
            <label className="field">
              <span>Плата</span>
              <select className="input" value={board} onChange={(e) => setField('board', e.target.value)}>
                {BOARDS.map((b) => <option key={b} value={b}>{b}</option>)}
              </select>
            </label>
          </div>

          <label className="field">
            <span>
              Описание <span className="hint">({description.length}/300)</span>
            </span>
            <textarea
              className="input"
              rows={3}
              value={description}
              maxLength={300}
              onChange={(e) => setField('description', e.target.value)}
              placeholder="Назначение образа"
            />
          </label>

          <div className="editor__deps">
            <div className="editor__deps-head">
              <h3>Зависимости ({selected.length})</h3>
              <span className="card__cost">{totalCost()} ед.</span>
            </div>
            {selected.length === 0 ? (
              <p className="hint">Компоненты не выбраны</p>
            ) : (
              <ul className="dep-list">
                {selected.map((c) => (
                  <li key={c.id} className="dep-item">
                    <span>{c.name}</span>
                    <span className="dep-item__type">{c.type?.type}</span>
                    <button className="icon-btn" title="Удалить"
                            onClick={() => removeComponent(c.id)}>✕</button>
                  </li>
                ))}
              </ul>
            )}
            <p className="hint">Транзитивные зависимости добавятся автоматически при сборке.</p>
          </div>

          <button className="btn btn--primary btn--block" disabled={busy} onClick={submit}>
            {busy ? 'Отправка…' : isEdit ? 'Сохранить и пересобрать' : 'Заказать образ'}
          </button>
          {isEdit && (
            <button className="btn btn--ghost btn--block" style={{ marginTop: 8 }}
                    onClick={() => { reset(); navigate('/library/images'); }}>
              Отмена
            </button>
          )}
        </section>

        {/* Правая колонка: каталог для добавления */}
        <aside className="editor__catalog">
          <h3>Добавить зависимость</h3>
          <div className="toolbar">
            <input className="input input--search" placeholder="Поиск…"
                   value={search} onChange={(e) => setSearch(e.target.value)} />
            <select className="input" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
              <option value="">Все</option>
              {types.map((t) => <option key={t.id} value={t.id}>{t.type}</option>)}
            </select>
          </div>
          {catalog.error && <div className="alert alert--error">{catalog.error}</div>}
          <ul className="catalog-list">
            {catalog.items.map((c) => (
              <li key={c.id} className="catalog-item">
                <div>
                  <div className="catalog-item__name">{c.name}</div>
                  <div className="catalog-item__type">{c.type?.type} · {c.cost} ед.</div>
                </div>
                <button className="btn btn--ghost btn--sm" disabled={isSelected(c.id)}
                        onClick={() => addComponent(c)}>
                  {isSelected(c.id) ? '✓' : '+'}
                </button>
              </li>
            ))}
            {!catalog.loading && catalog.items.length === 0 && <li className="hint">Ничего не найдено</li>}
          </ul>
          <Pagination page={catalog.page} totalPages={catalog.totalPages}
                      totalElements={catalog.totalElements} onChange={catalog.setPage} />
        </aside>
      </div>
    </div>
  );
}
