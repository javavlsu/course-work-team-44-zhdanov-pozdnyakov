import { create } from 'zustand';

const EMPTY = {
  editingId: null, // id образа при редактировании (админ), null — новый образ
  name: '',
  description: '',
  distro: 'buildroot',
  board: 'RV1103_Luckfox_Pico',
  selected: [], // массив компонентов { id, name, type, cost, ... }
};

// Состояние редактора образа: выбранные компоненты и параметры до отправки на сервер.
export const useEditorStore = create((set, get) => ({
  ...EMPTY,

  setField: (field, value) => set({ [field]: value }),

  addComponent: (component) => {
    if (get().selected.some((c) => c.id === component.id)) return;
    set((state) => ({ selected: [...state.selected, component] }));
  },

  removeComponent: (id) =>
    set((state) => ({ selected: state.selected.filter((c) => c.id !== id) })),

  isSelected: (id) => get().selected.some((c) => c.id === id),

  totalCost: () => get().selected.reduce((sum, c) => sum + (c.cost || 0), 0),

  // Заполнение редактора существующим образом.
  loadImage: (image) =>
    set({
      editingId: image.id,
      name: image.name || '',
      description: image.description || '',
      distro: image.distro || 'buildroot',
      board: image.board || 'RV1103_Luckfox_Pico',
      selected: image.components || [],
    }),

  reset: () => set(EMPTY),
}));
