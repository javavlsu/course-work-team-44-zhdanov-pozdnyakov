import React from 'react';
import StatusBadge from './StatusBadge';

export default function ImageCard({ image, onDownload }) {
  return (
    <article className="card">
      <div className="card__header">
        <h3 className="card__title">{image.name || `Образ #${image.id}`}</h3>
        <StatusBadge state={image.state} />
      </div>
      <p className="card__desc">{image.description || 'Без описания'}</p>
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
    </article>
  );
}
