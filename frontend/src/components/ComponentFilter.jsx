import React, { useEffect, useState } from 'react';
import { apiGetComponents } from '../api/api';

// Выбор нескольких компонентов для фильтра: поиск с подсказками + выбранные чипы.
// selected — [{ id, name }], onChange(newSelected).
export default function ComponentFilter({ selected, onChange }) {
  const [query, setQuery] = useState('');
  const [options, setOptions] = useState([]);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!query.trim()) { setOptions([]); return undefined; }
    const t = setTimeout(() => {
      apiGetComponents({ search: query.trim(), size: 8 })
        .then(({ data }) => setOptions(data.content))
        .catch(() => setOptions([]));
    }, 250);
    return () => clearTimeout(t);
  }, [query]);

  const add = (c) => {
    onChange([...selected, { id: c.id, name: c.name }]);
    setQuery('');
    setOpen(false);
  };

  const remove = (id) => onChange(selected.filter((c) => c.id !== id));
  const visible = options.filter((o) => !selected.some((s) => s.id === o.id));

  return (
    <div className="picker">
      <input
        className="input"
        placeholder="Фильтр по компонентам…"
        value={query}
        onChange={(e) => { setQuery(e.target.value); setOpen(true); }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)} // даём сработать клику по пункту
      />
      {open && query.trim() && (
        <ul className="picker__menu">
          {visible.map((c) => (
            <li key={c.id}>
              <button type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => add(c)}>
                {c.name} <span className="catalog-item__type">{c.type?.type}</span>
              </button>
            </li>
          ))}
          {visible.length === 0 && <li className="hint picker__empty">Ничего не найдено</li>}
        </ul>
      )}
      {selected.length > 0 && (
        <div className="card__chips picker__chips">
          {selected.map((c) => (
            <button type="button" key={c.id} className="chip chip--removable"
                    title="Убрать из фильтра" onClick={() => remove(c.id)}>{c.name} ✕</button>
          ))}
          <button type="button" className="chip chip--removable" onClick={() => onChange([])}>
            Сбросить
          </button>
        </div>
      )}
    </div>
  );
}
