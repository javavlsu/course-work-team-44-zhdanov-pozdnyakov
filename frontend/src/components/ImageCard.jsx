import React from 'react';
import StatusBadge from './StatusBadge';

export default function ImageCard({ image, onDownload }) {
  const description_text = image.description || 'Без описания'
  const name_text = image.name || `Образ #${image.id}`
  return (
    <article className="card">
      <div className="card__header">
        <h3 className="card__title">
          
                  {name_text.length > 40 ? name_text.slice(0, MAX_LABEL - 1) + '…' : name_text}
        </h3>
        <StatusBadge state={image.state} />
      </div>
      <span className="card__desc">

        {description_text.length > 300 ? description_text.slice(0, MAX_LABEL - 1) + '…' : description_text}
      </span>
      {/* <p className="card__desc" onChange={(e) => setField('name', e.target.value)} maxLength={300} >{image.description || 'Без описания'}</p> */}
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
