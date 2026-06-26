import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiGetComponents, apiGetComponentTypes, apiBuildImage, extractError } from '../api/api';
import { useEditorStore } from '../store/editorStore';

const DISTROS = ['buildroot', 'ubuntu', 'busybox'];
const BOARDS = [
  'RV1103_Luckfox_Pico',
  'RV1103_Luckfox_Pico_Mini',
  'RV1103_Luckfox_Pico_Plus',
  'RV1106_Luckfox_Pico_Pro_Max',
];

export default function ImageEditor() {
  const navigate = useNavigate();
  const {
    name, description, distro, board, selected,
    setField, addComponent, removeComponent, isSelected, totalCost, reset,
  } = useEditorStore();

  const [available, setAvailable] = useState([]);
  const [types, setTypes] = useState([]);
  const [search, setSearch] = useState('');
  const [typeId, setTypeId] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    apiGetComponentTypes().then(({ data }) => setTypes(data)).catch(() => {});
  }, []);

  useEffect(() => {
    const t = setTimeout(() => {
      const params = {};
      if (search) params.search = search;
      if (typeId) params.type = typeId;
      apiGetComponents(params).then(({ data }) => setAvailable(data)).catch(() => {});
    }, 250);
    return () => clearTimeout(t);
  }, [search, typeId]);

  const submit = async () => {
    setError('');
    if (!name.trim()) return setError('Укажите название образа');
    if (selected.length === 0) return setError('Добавьте хотя бы один компонент');
    setBusy(true);
    try {
      await apiBuildImage({
        name, description, distro, board,
        componentIds: selected.map((c) => c.id),
      });
      reset();
      navigate('/library/images');
    } catch (e) {
      setError(extractError(e, 'Не удалось заказать сборку'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="page editor">
      <h1 className="page__title">Создать образ</h1>
      {error && <div className="alert alert--error">{error}</div>}

      <div className="editor__layout">
        {/* Левая колонка: параметры образа и выбранные зависимости */}
        <section className="editor__panel">
          <label className="field">
            <span>Название</span>
            <input className="input" value={name}
                   onChange={(e) => setField('name', e.target.value)} placeholder="my-image" />
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
            <span>Описание</span>
            <textarea className="input" rows={3} value={description}
                      onChange={(e) => setField('description', e.target.value)}
                      placeholder="Назначение образа" />
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
            {busy ? 'Отправка…' : 'Заказать образ'}
          </button>
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
          <ul className="catalog-list">
            {available.map((c) => (
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
            {available.length === 0 && <li className="hint">Ничего не найдено</li>}
          </ul>
        </aside>
      </div>
    </div>
  );
}
