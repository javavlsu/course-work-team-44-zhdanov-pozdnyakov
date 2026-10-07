import React from 'react';

export const LABELS = {
  CREATED: 'Создан',
  QUEUED: 'В очереди',
  BUILDING: 'Сборка',
  READY: 'Готов',
  FAILED: 'Ошибка',
};

export default function StatusBadge({ state }) {
  const key = (state || '').toUpperCase();
  return (
    <span className={`badge badge--${key.toLowerCase()}`}>
      {LABELS[key] || state}
    </span>
  );
}
