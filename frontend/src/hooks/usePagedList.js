import { useCallback, useEffect, useRef, useState } from 'react';
import { extractError } from '../api/api';

// Постраничный список с поиском/фильтрами.
// fetchPage(params) → axios-ответ с { content, totalPages, totalElements }.
// filters — объект { search, type, ... }; пустые значения в запрос не попадают.
// При смене фильтров список возвращается на первую страницу.
export default function usePagedList(fetchPage, filters, { size = 12, pollMs = 0 } = {}) {
  const key = JSON.stringify(filters);
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], totalPages: 0, totalElements: 0 });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;
  const seq = useRef(0); // отбрасываем устаревшие ответы

  useEffect(() => { setPage(0); }, [key]);

  const load = useCallback(async () => {
    const my = ++seq.current;
    const params = { page, size };
    Object.entries(JSON.parse(key)).forEach(([k, v]) => {
      if (v !== '' && v != null) params[k] = v;
    });
    try {
      const { data } = await fetchRef.current(params);
      if (my !== seq.current) return;
      setData(data);
      setError('');
    } catch (e) {
      if (my === seq.current) setError(extractError(e, 'Не удалось загрузить список'));
    } finally {
      if (my === seq.current) setLoading(false);
    }
  }, [key, page, size]);

  useEffect(() => {
    const t = setTimeout(load, 250); // debounce ввода в поиск
    const i = pollMs ? setInterval(load, pollMs) : null;
    return () => { clearTimeout(t); if (i) clearInterval(i); };
  }, [load, pollMs]);

  // Если после удаления текущая страница опустела — шаг назад.
  useEffect(() => {
    if (page > 0 && page >= data.totalPages) setPage(Math.max(data.totalPages - 1, 0));
  }, [page, data.totalPages]);

  return {
    items: data.content, page, totalPages: data.totalPages, totalElements: data.totalElements,
    loading, error, setPage, reload: load,
  };
}
