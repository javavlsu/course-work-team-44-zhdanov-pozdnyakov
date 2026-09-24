import React from 'react';

const MAX_LABEL_LENGTH = 20;

export default function ComponentCard({ component, action, onAction, actionLabel, disabled }) {
  const displayLabel =
  actionLabel && actionLabel.length > MAX_LABEL_LENGTH
    ? actionLabel.slice(0, MAX_LABEL_LENGTH - 1) + '…'
    : actionLabel;
  return (
    <article className="card card--component">
      <div className="card__header">
        <h3 className="card__title">{component.name}</h3>
        {component.type && <span className="tag tag--brand">{component.type.type}</span>}
      </div>
      <p className="card__desc">{component.description || 'Без описания'}</p>
      <div className="card__footer">
        <span className="card__cost">{component.cost} ед.</span>
        {action && (
          <button
            className="btn btn--primary btn--sm"
            disabled={disabled}
            onClick={() => onAction?.(component)}
            title={actionLabel}  // full text on hover
          >
            {displayLabel}
          </button>
        )}
      </div>
    </article>
  );
}
