import React, { useEffect, useState } from 'react';
import { apiGetMyImages, apiDownloadUrl, extractError } from '../api/api';
import { useAuth } from '../context/AuthContext';
import ImageCard from '../components/ImageCard';

export default function Account() {
  const { user } = useAuth();
  const [images, setImages] = useState([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = async () => {
    try {
      const { data } = await apiGetMyImages();
      setImages(data);
    } catch (e) {
      setError(extractError(e, 'Не удалось загрузить заказы'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, []);

  const download = (image) => {
    const token = localStorage.getItem('token');
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
      <h1 className="page__title">Аккаунт</h1>
      {user && (
        <div className="profile">
          <div><span className="profile__label">Имя</span>{user.name} {user.surname || ''}</div>
          <div><span className="profile__label">Email</span>{user.email}</div>
          <div><span className="profile__label">Роли</span>{(user.roles || []).join(', ') || 'USER'}</div>
        </div>
      )}

      <h2 className="page__subtitle" style={{ marginTop: 24 }}>Мои образы</h2>
      {error && <div className="alert alert--error">{error}</div>}
      {loading ? (
        <div className="empty">Загрузка…</div>
      ) : images.length === 0 ? (
        <div className="empty">Вы ещё не заказывали сборки</div>
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
