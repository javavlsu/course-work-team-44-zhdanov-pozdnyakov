import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { apiGetImages, apiDownloadUrl, extractError } from '../api/api';
import { useAuth } from '../context/AuthContext';
import ImageCard from '../components/ImageCard';

export default function ImageLibrary() {
  const [images, setImages] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const { isAuthenticated } = useAuth();
  const navigate = useNavigate();

  const load = async () => {
    try {
      const { data } = await apiGetImages();
      setImages(data);
    } catch (e) {
      setError(extractError(e, 'Не удалось загрузить образы'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // Автообновление статусов сборок, пока есть незавершённые.
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, []);

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
      .catch((e) => setError(e.message));
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

      {error && <div className="alert alert--error">{error}</div>}
      {loading ? (
        <div className="empty">Загрузка…</div>
      ) : images.length === 0 ? (
        <div className="empty">Образов пока нет</div>
      ) : (
        <div className="grid">
          {images.map((img) => (
            <ImageCard key={img.id} image={img} onDownload={download} />
          ))}
        </div>
      )}
    </div>
  );
}
