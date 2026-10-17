import { Text, View } from 'react-native';

import { radius, spacing, useColors, useScheme } from '@/theme';

import { categoryLook, tint } from './appearance';

/** Round icon in the category's color, e.g. 🛒 on green for Groceries. */
export function CategoryIcon({ categoryId, size = 36 }: { categoryId: string | null; size?: number }) {
  const look = categoryLook(categoryId, useScheme());
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        backgroundColor: tint(look.color),
        alignItems: 'center',
        justifyContent: 'center',
      }}>
      <Text style={{ fontSize: size * 0.5 }}>{look.icon}</Text>
    </View>
  );
}

/** Category name as a small colored pill. */
export function CategoryPill({ categoryId, name }: { categoryId: string | null; name: string }) {
  const look = categoryLook(categoryId, useScheme());
  const c = useColors();
  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
        alignSelf: 'flex-start',
        paddingHorizontal: spacing.sm,
        paddingVertical: 2,
        borderRadius: radius.pill,
        backgroundColor: tint(look.color),
      }}>
      <Text style={{ fontSize: 11 }}>{look.icon}</Text>
      <Text style={{ fontSize: 12, fontWeight: '600', color: c.text }}>{name}</Text>
    </View>
  );
}
