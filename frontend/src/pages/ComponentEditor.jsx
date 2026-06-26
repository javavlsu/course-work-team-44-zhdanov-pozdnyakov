import React, { useEffect, useState } from 'react';
import {
  apiGetComponents, apiGetComponentTypes, apiCreateComponent,
  apiDeleteComponent, extractError,
} from '../api/api';

export default function ComponentEditor() {
  const [form, setForm] = useState({ name: '', description: '', typeId: '', cost: 0, dependencyIds: [] });
  const [types, setTypes] = useState([]);
  const [components, setComponents] = useState([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);

  const load = async () => {
    const [{ data: t }, { data: c }] = await Promise.all([
      apiGetComponentTypes(), apiGetComponents(),
    ]);
    setTypes(t);
    setComponents(c);
    if (t.length && !form.typeId) setForm((f) => ({ ...f, typeId: String(t[0].id) }));
  };

  useEffect(() => { load().catch((e) => setError(extractError(e))); }, []);

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const toggleDep = (id) => {
    setForm((f) => ({
      ...f,
      dependencyIds: f.dependencyIds.includes(id)
        ? f.dependencyIds.filter((d) => d !== id)
        : [...f.dependencyIds, id],
    }));
  };

  const submit = async (e) => {
    e.preventDefault();
    setError(''); setOk(''); setBusy(true);
    try {
      await apiCreateComponent({
        name: form.name,
        description: form.description,
        typeId: Number(form.typeId),
        cost: Number(form.cost),
        dependencyIds: form.dependencyIds,
      });
      setOk(`Компонент «${form.name}» создан`);
      setForm({ name: '', description: '', typeId: String(types[0]?.id || ''), cost: 0, dependencyIds: [] });
      await load();
    } catch (err) {
      setError(extractError(err, 'Не удалось создать компонент'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id) => {
    setError(''); setOk('');
    try {
      await apiDeleteComponent(id);
      await load();
    } catch (err) {
      setError(extractError(err, 'Не удалось удалить (возможно, используется в образах)'));
    }
  };

  return (
    <div className="page editor">
      <h1 className="page__title">Редактор компонентов</h1>
      <p className="page__subtitle">Доступно администраторам</p>
      {error && <div className="alert alert--error">{error}</div>}
      {ok && <div className="alert alert--ok">{ok}</div>}

      <div className="editor__layout">
        <form className="editor__panel" onSubmit={submit}>
          <label className="field">
            <span>Название</span>
            <input className="input" name="name" value={form.name} required onChange={onChange} />
          </label>
          <div className="field-row">
            <label className="field">
              <span>Тип</span>
              <select className="input" name="typeId" value={form.typeId} onChange={onChange}>
                {types.map((t) => <option key={t.id} value={t.id}>{t.type}</option>)}
              </select>
            </label>
            <label className="field">
              <span>Стоимость</span>
              <input className="input" type="number" name="cost" min="0"
                     value={form.cost} onChange={onChange} />
            </label>
          </div>
          <label className="field">
            <span>Описание</span>
            <textarea className="input" rows={3} name="description"
                      value={form.description} onChange={onChange} />
          </label>

          <div className="editor__deps">
            <h3>Зависимости компонента</h3>
            <div className="dep-checks">
              {components.map((c) => (
                <label key={c.id} className="dep-check">
                  <input type="checkbox"
                         checked={form.dependencyIds.includes(c.id)}
                         onChange={() => toggleDep(c.id)} />
                  {c.name}
                </label>
              ))}
              {components.length === 0 && <span className="hint">Пока нет компонентов</span>}
            </div>
          </div>

          <button className="btn btn--primary btn--block" disabled={busy}>
            {busy ? 'Сохранение…' : 'Создать компонент'}
          </button>
        </form>

        <aside className="editor__catalog">
          <h3>Существующие ({components.length})</h3>
          <ul className="catalog-list">
            {components.map((c) => (
              <li key={c.id} className="catalog-item">
                <div>
                  <div className="catalog-item__name">{c.name}</div>
                  <div className="catalog-item__type">{c.type?.type} · {c.cost} ед.</div>
                </div>
                <button className="icon-btn" title="Удалить" onClick={() => remove(c.id)}>✕</button>
              </li>
            ))}
          </ul>
        </aside>
      </div>
    </div>
  );
}
