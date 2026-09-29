import React from 'react';

export default function Pagination({ page, totalPages, totalElements, onChange }) {
  if (totalPages <= 1) {
    return totalElements > 0 ? <div className="pagination"><span>Всего: {totalElements}</span></div> : null;
  }
  return (
    <div className="pagination">
      <button className="btn btn--ghost btn--sm" disabled={page === 0} onClick={() => onChange(0)}>«</button>
      <button className="btn btn--ghost btn--sm" disabled={page === 0} onClick={() => onChange(page - 1)}>‹ Назад</button>
      <span>Стр. {page + 1} из {totalPages} · всего {totalElements}</span>
      <button className="btn btn--ghost btn--sm" disabled={page >= totalPages - 1}
              onClick={() => onChange(page + 1)}>Вперёд ›</button>
      <button className="btn btn--ghost btn--sm" disabled={page >= totalPages - 1}
              onClick={() => onChange(totalPages - 1)}>»</button>
    </div>
  );
}
