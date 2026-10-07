import React from 'react';
import StatusBadge from './StatusBadge';

const MAX_NAME = 40;
const MAX_DESC = 300;

const cut = (text, max) => (text.length > max ? text.slice(0, max - 1) + '…' : text);

export default function ImageCard({ image, onDownload, onEdit, onDelete }) {
  const busy = image.state === 'QUEUED' || image.state === 'BUILDING';
  return (
    <article className="card">
      <div className="card__header">
        <h3 className="card__title">{cut(image.name || `Образ #${image.id}`, MAX_NAME)}</h3>
        <StatusBadge state={image.state} />
      </div>
      <span className="card__desc">{cut(image.description || 'Без описания', MAX_DESC)}</span>
      <div className="card__meta">
        <span className="tag">{image.distro || '—'}</span>
        <span className="tag">{image.board || '—'}</span>
      </div>
      {image.components?.length > 0 && (
        <div className="card__chips">
          {image.components.slice(0, 6).map((c) => (
            <span key={c.id} className="chip">{c.name}</span>
          ))}
          {image.components.length > 6 && (
            <span className="chip chip--muted">+{image.components.length - 6}</span>
          )}
        </div>
      )}
      <div className="card__footer">
        <span className="card__cost">{image.cost} ед.</span>
        {image.downloadable && (
          <button className="btn btn--primary btn--sm" onClick={() => onDownload?.(image)}>
            Скачать .img
          </button>
        )}
      </div>
      {(onEdit || onDelete) && (
        <div className="card__admin">
          {onEdit && (
            <button className="btn btn--ghost btn--sm" disabled={busy}
                    title={busy ? 'Дождитесь окончания сборки' : 'Изменить и пересобрать'}
                    onClick={() => onEdit(image)}>Изменить</button>
          )}
          {onDelete && (
            <button className="btn btn--danger btn--sm" disabled={image.state === 'BUILDING'}
                    onClick={() => onDelete(image)}>Удалить</button>
          )}
        </div>
      )}
    </article>
  );
}
