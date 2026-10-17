export type CategoryLook = { icon: string; color: string };
export type Scheme = 'light' | 'dark';

/**
 * Validated 8-hue categorical palette (blue, orange, aqua, yellow, magenta, green, violet, red),
 * with separate steps for each theme. Checked for colour-blind separation on both surfaces.
 */
const PALETTE: Record<Scheme, string[]> = {
  light: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'],
  dark: ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'],
};
const NEUTRAL: Record<Scheme, string> = { light: '#8a8984', dark: '#8f8e88' };

const BLUE = 0, ORANGE = 1, AQUA = 2, YELLOW = 3, MAGENTA = 4, GREEN = 5, VIOLET = 6, RED = 7;

/** Each category keeps its colour everywhere (icon, pill, chart), whatever its rank. */
const LOOKS: Record<string, { icon: string; slot: number | null }> = {
  groceries: { icon: '🛒', slot: GREEN },
  restaurant: { icon: '🍽️', slot: ORANGE },
  transport: { icon: '🚕', slot: BLUE },
  fuel: { icon: '⛽', slot: YELLOW },
  shopping: { icon: '🛍️', slot: MAGENTA },
  bills: { icon: '💡', slot: AQUA },
  rent: { icon: '🏠', slot: VIOLET },
  subscriptions: { icon: '🔁', slot: RED },
  health: { icon: '💊', slot: RED },
  entertainment: { icon: '🎬', slot: MAGENTA },
  travel: { icon: '✈️', slot: BLUE },
  transfers: { icon: '↔️', slot: AQUA },
  remittance: { icon: '💸', slot: ORANGE },
  other: { icon: '🏷️', slot: null },
  salary: { icon: '💼', slot: GREEN },
  refund: { icon: '↩️', slot: AQUA },
  'transfer-in': { icon: '📥', slot: BLUE },
  'other-income': { icon: '💰', slot: VIOLET },
};

export function categoryLook(categoryId: string | null, scheme: Scheme = 'light'): CategoryLook {
  if (!categoryId) return { icon: '❔', color: NEUTRAL[scheme] };
  const known = LOOKS[categoryId];
  if (known) return { icon: known.icon, color: known.slot === null ? NEUTRAL[scheme] : PALETTE[scheme][known.slot] };
  // Categories the user added: a stable slot from the id.
  let h = 0;
  for (let i = 0; i < categoryId.length; i++) h = (h * 31 + categoryId.charCodeAt(i)) | 0;
  return { icon: '🏷️', color: PALETTE[scheme][Math.abs(h) % 8] };
}

/** Colour for the chart's "Other" slice. */
export function otherColor(scheme: Scheme): string {
  return NEUTRAL[scheme];
}

/** The accent colour at low opacity, for icon backgrounds and pills. */
export function tint(color: string): string {
  return `${color}26`;
}
