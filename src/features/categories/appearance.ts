export type CategoryLook = { icon: string; color: string };

/** Icon and accent color per default category. Colors are mid-tones that read on light and dark. */
const LOOKS: Record<string, CategoryLook> = {
  groceries: { icon: '🛒', color: '#2E9E5B' },
  restaurant: { icon: '🍽️', color: '#E2703A' },
  transport: { icon: '🚕', color: '#3B82F6' },
  fuel: { icon: '⛽', color: '#D97706' },
  shopping: { icon: '🛍️', color: '#C2479A' },
  bills: { icon: '💡', color: '#CA8A04' },
  rent: { icon: '🏠', color: '#7C5CDB' },
  health: { icon: '💊', color: '#E0475B' },
  entertainment: { icon: '🎬', color: '#9B51E0' },
  subscriptions: { icon: '🔁', color: '#0EA5B7' },
  travel: { icon: '✈️', color: '#2563EB' },
  transfers: { icon: '↔️', color: '#64748B' },
  other: { icon: '🏷️', color: '#78716C' },
  salary: { icon: '💼', color: '#16A34A' },
  refund: { icon: '↩️', color: '#0D9488' },
  'other-income': { icon: '💰', color: '#15803D' },
};

const UNCATEGORISED: CategoryLook = { icon: '❔', color: '#94A3B8' };

/** Categories the user added get a stable color from their id. */
const EXTRA_COLORS = ['#0891B2', '#DB2777', '#65A30D', '#EA580C', '#4F46E5', '#B45309'];

export function categoryLook(categoryId: string | null): CategoryLook {
  if (!categoryId) return UNCATEGORISED;
  const known = LOOKS[categoryId];
  if (known) return known;
  let h = 0;
  for (let i = 0; i < categoryId.length; i++) h = (h * 31 + categoryId.charCodeAt(i)) | 0;
  return { icon: '🏷️', color: EXTRA_COLORS[Math.abs(h) % EXTRA_COLORS.length] };
}

/** The accent color at low opacity, for icon backgrounds and pills. */
export function tint(color: string): string {
  return `${color}26`;
}
