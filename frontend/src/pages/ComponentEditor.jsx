import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  apiGetComponents, apiGetComponent, apiGetComponentTypes, apiCreateComponent,
  apiUpdateComponent, apiDeleteComponent, extractError,
} from '../api/api';
import Pagination from '../components/Pagination';
import usePagedList from '../hooks/usePagedList';

const EMPTY = { name: '', description: '', typeId: '', cost: 0, deps: [] }; // deps: [{ id, name }]

export default function ComponentEditor() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const editId = params.get('id');

  const [form, setForm] = useState(EMPTY);
  const [types, setTypes] = useState([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);

  // Выбор зависимостей: поиск по каталогу.
  const [depSearch, setDepSearch] = useState('');
  const depList = usePagedList(apiGetComponents, { search: depSearch }, { size: 8 });

  // Список существующих компонентов: поиск + фильтр по типу.
  const [search, setSearch] = useState('');
  const [typeId, setTypeId] = useState('');
  const list = usePagedList(apiGetComponents, { search, type: typeId }, { size: 10 });

  const defaultTypeId = () => String(types[0]?.id || '');

  useEffect(() => {
    apiGetComponentTypes()
      .then(({ data }) => {
        setTypes(data);
        setForm((f) => (f.typeId || !data.length ? f : { ...f, typeId: String(data[0].id) }));
      })
      .catch((e) => setError(extractError(e)));
  }, []);

  // Режим редактирования (?id=) — подставляем компонент в форму.
  useEffect(() => {
    setError(''); setOk('');
    if (!editId) {
      setForm((f) => ({ ...EMPTY, typeId: f.typeId }));
      return;
    }
    apiGetComponent(editId)
      .then(({ data }) => setForm({
        name: data.name,
        description: data.description || '',
        typeId: String(data.type?.id || ''),
        cost: data.cost,
        deps: data.dependencies || [],
      }))
      .catch((e) => setError(extractError(e, 'Не удалось загрузить компонент')));
  }, [editId]);

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const toggleDep = (c) => {
    setForm((f) => ({
      ...f,
      deps: f.deps.some((d) => d.id === c.id)
        ? f.deps.filter((d) => d.id !== c.id)
        : [...f.deps, { id: c.id, name: c.name }],
    }));
  };

  const submit = async (e) => {
    e.preventDefault();
    setError(''); setOk(''); setBusy(true);
    const body = {
      name: form.name,
      description: form.description,
      typeId: Number(form.typeId),
      cost: Number(form.cost),
      dependencyIds: form.deps.map((d) => d.id),
    };
    try {
      if (editId) {
        await apiUpdateComponent(editId, body);
        setOk(`Компонент «${form.name}» сохранён`);
      } else {
        await apiCreateComponent(body);
        setOk(`Компонент «${form.name}» создан`);
        setForm({ ...EMPTY, typeId: defaultTypeId() });
      }
      await Promise.all([list.reload(), depList.reload()]);
    } catch (err) {
      setError(extractError(err, editId ? 'Не удалось сохранить компонент' : 'Не удалось создать компонент'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (c) => {
    if (!window.confirm(`Удалить компонент «${c.name}»?`)) return;
    setError(''); setOk('');
    try {
      await apiDeleteComponent(c.id);
      if (String(c.id) === editId) navigate('/editor/component');
      await Promise.all([list.reload(), depList.reload()]);
    } catch (err) {
      setError(extractError(err, 'Не удалось удалить компонент'));
    }
  };

  const currentId = editId ? Number(editId) : null;

  return (
    <div className="page editor">
      <h1 className="page__title">{editId ? `Редактирование компонента #${editId}` : 'Редактор компонентов'}</h1>
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
            <h3>Зависимости компонента ({form.deps.length})</h3>
            {form.deps.length > 0 && (
              <div className="card__chips" style={{ marginBottom: 10 }}>
                {form.deps.map((d) => (
                  <button type="button" key={d.id} className="chip chip--removable"
                          title="Убрать" onClick={() => toggleDep(d)}>{d.name} ✕</button>
                ))}
              </div>
            )}
            <input className="input input--search" placeholder="Найти компонент…" style={{ width: '100%' }}
                   value={depSearch} onChange={(e) => setDepSearch(e.target.value)} />
            <div className="dep-checks" style={{ marginTop: 8 }}>
              {depList.items.filter((c) => c.id !== currentId).map((c) => (
                <label key={c.id} className="dep-check">
                  <input type="checkbox"
                         checked={form.deps.some((d) => d.id === c.id)}
                         onChange={() => toggleDep(c)} />
                  {c.name}
                </label>
              ))}
              {!depList.loading && depList.items.length === 0 && <span className="hint">Ничего не найдено</span>}
            </div>
            <Pagination page={depList.page} totalPages={depList.totalPages}
                        totalElements={depList.totalElements} onChange={depList.setPage} />
          </div>

          <button className="btn btn--primary btn--block" disabled={busy}>
            {busy ? 'Сохранение…' : editId ? 'Сохранить изменения' : 'Создать компонент'}
          </button>
          {editId && (
            <button type="button" className="btn btn--ghost btn--block" style={{ marginTop: 8 }}
                    onClick={() => navigate('/editor/component')}>
              Отмена — создать новый
            </button>
          )}
        </form>

        <aside className="editor__catalog">
          <h3>Существующие ({list.totalElements})</h3>
          <div className="toolbar">
            <input className="input input--search" placeholder="Поиск…"
                   value={search} onChange={(e) => setSearch(e.target.value)} />
            <select className="input" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
              <option value="">Все</option>
              {types.map((t) => <option key={t.id} value={t.id}>{t.type}</option>)}
            </select>
          </div>
          {list.error && <div className="alert alert--error">{list.error}</div>}
          <ul className="catalog-list">
            {list.items.map((c) => (
              <li key={c.id} className={`catalog-item${c.id === currentId ? ' catalog-item--active' : ''}`}>
                <div>
                  <div className="catalog-item__name">{c.name}</div>
                  <div className="catalog-item__type">{c.type?.type} · {c.cost} ед.</div>
                </div>
                <div className="catalog-item__actions">
                  <button className="icon-btn icon-btn--edit" title="Изменить"
                          onClick={() => navigate(`/editor/component?id=${c.id}`)}>✎</button>
                  <button className="icon-btn" title="Удалить" onClick={() => remove(c)}>✕</button>
                </div>
              </li>
            ))}
            {!list.loading && list.items.length === 0 && <li className="hint">Ничего не найдено</li>}
          </ul>
          <Pagination page={list.page} totalPages={list.totalPages}
                      totalElements={list.totalElements} onChange={list.setPage} />
        </aside>
      </div>
    </div>
  );
}
