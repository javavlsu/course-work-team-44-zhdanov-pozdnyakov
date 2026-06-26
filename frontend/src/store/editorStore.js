import { create } from 'zustand';

// Состояние редактора образа: выбранные компоненты и параметры до отправки на сервер.
export const useEditorStore = create((set, get) => ({
  name: '',
  description: '',
  distro: 'buildroot',
  board: 'RV1103_Luckfox_Pico',
  selected: [], // массив компонентов { id, name, type, cost, ... }

  setField: (field, value) => set({ [field]: value }),

  addComponent: (component) => {
    if (get().selected.some((c) => c.id === component.id)) return;
    set((state) => ({ selected: [...state.selected, component] }));
  },

  removeComponent: (id) =>
    set((state) => ({ selected: state.selected.filter((c) => c.id !== id) })),

  isSelected: (id) => get().selected.some((c) => c.id === id),

  totalCost: () => get().selected.reduce((sum, c) => sum + (c.cost || 0), 0),

  reset: () =>
    set({
      name: '',
      description: '',
      distro: 'buildroot',
      board: 'RV1103_Luckfox_Pico',
      selected: [],
    }),
}));
