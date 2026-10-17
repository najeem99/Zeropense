import { useColorScheme } from 'react-native';

const light = {
  background: '#F6F7F9',
  surface: '#FFFFFF',
  surfaceAlt: '#EEF0F3',
  border: '#E1E4E8',
  text: '#11181C',
  textMuted: '#687076',
  primary: '#1B6EF3',
  onPrimary: '#FFFFFF',
  income: '#13804B',
  expense: '#C9372C',
  warning: '#A35F00',
  warningBg: '#FFF4E0',
};

const dark: typeof light = {
  background: '#0E1012',
  surface: '#181B1F',
  surfaceAlt: '#22262B',
  border: '#2D3238',
  text: '#ECEDEE',
  textMuted: '#9BA1A6',
  primary: '#5B9BFF',
  onPrimary: '#0E1012',
  income: '#4CC38A',
  expense: '#FF6B5E',
  warning: '#F5B342',
  warningBg: '#3A2A0E',
};

export type Palette = typeof light;

export const spacing = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24 } as const;
export const radius = { sm: 8, md: 12, lg: 16, pill: 999 } as const;

export function useColors(): Palette {
  return useColorScheme() === 'dark' ? dark : light;
}

export function useScheme(): 'light' | 'dark' {
  return useColorScheme() === 'dark' ? 'dark' : 'light';
}
