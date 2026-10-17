import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import Svg, { Circle, G } from 'react-native-svg';

import { categoryLook, otherColor } from '@/features/categories/appearance';
import { formatMoney, type Currency } from '@/lib/money';
import { spacing, useColors, useScheme } from '@/theme';

/** Beyond this many categories the rest fold into one grey "Other" slice. */
const MAX_SLICES = 5;
const SIZE = 168;
const STROKE = 22;
/** Surface gap between slices, in px along the ring. */
const GAP = 2;

export type Slice = { key: string; categoryId: string | null; label: string; icon: string; color: string; minor: number };

export function buildSlices(
  spending: { categoryId: string | null; minor: number }[],
  nameOf: (id: string | null) => string,
  scheme: 'light' | 'dark',
): Slice[] {
  const sorted = [...spending].sort((a, b) => b.minor - a.minor);
  const head = sorted.length > MAX_SLICES ? sorted.slice(0, MAX_SLICES - 1) : sorted;
  const rest = sorted.slice(head.length);
  const slices: Slice[] = head.map((s) => {
    const look = categoryLook(s.categoryId, scheme);
    return { key: s.categoryId ?? 'none', categoryId: s.categoryId, label: nameOf(s.categoryId), icon: look.icon, color: look.color, minor: s.minor };
  });
  if (rest.length > 0) {
    slices.push({
      key: 'other-slice',
      categoryId: null,
      label: `Other (${rest.length})`,
      icon: '•••',
      color: otherColor(scheme),
      minor: rest.reduce((sum, s) => sum + s.minor, 0),
    });
  }
  return slices;
}

/** Dash length and start offset of each slice along the ring, with a small gap between slices. */
export function layoutArcs(slices: Slice[], circumference: number): { slice: Slice; dash: number; offset: number }[] {
  const total = slices.reduce((sum, s) => sum + s.minor, 0);
  const starts = slices.map((_, i) => slices.slice(0, i).reduce((sum, s) => sum + s.minor, 0));
  return slices.map((slice, i) => {
    const length = (slice.minor / total) * circumference;
    return {
      slice,
      dash: Math.max(0, length - (slices.length > 1 ? GAP : 0)),
      offset: (starts[i] / total) * circumference,
    };
  });
}

/**
 * Where the money went, as a donut with a legend. Tap a slice or a legend row to see its
 * amount and share in the middle; tap again to go back to the total.
 */
export function SpendingDonut({
  spending,
  currency,
  nameOf,
}: {
  spending: { categoryId: string | null; minor: number }[];
  currency: Currency;
  nameOf: (id: string | null) => string;
}) {
  const c = useColors();
  const scheme = useScheme();
  const [selected, setSelected] = useState<string | null>(null);

  const slices = buildSlices(spending, nameOf, scheme);
  const total = slices.reduce((sum, s) => sum + s.minor, 0);
  if (total === 0) return null;

  const r = (SIZE - STROKE) / 2;
  const circumference = 2 * Math.PI * r;
  const active = slices.find((s) => s.key === selected) ?? null;
  const pct = (minor: number) => `${Math.round((minor / total) * 100)}%`;
  const toggle = (key: string) => setSelected(selected === key ? null : key);

  const arcs = layoutArcs(slices, circumference);

  return (
    <View style={{ gap: spacing.md, marginTop: spacing.sm }}>
      <Text style={{ fontSize: 13, fontWeight: '600', color: c.textMuted, textTransform: 'uppercase', letterSpacing: 0.4 }}>
        Spending by category
      </Text>
      <View style={{ alignItems: 'center', justifyContent: 'center' }}>
        <Svg width={SIZE} height={SIZE} accessibilityLabel={`Spending by category, total ${formatMoney(total, currency)}`}>
          <G rotation={-90} origin={`${SIZE / 2}, ${SIZE / 2}`}>
            {arcs.map(({ slice, dash, offset: o }) => (
              <Circle
                key={slice.key}
                cx={SIZE / 2}
                cy={SIZE / 2}
                r={r}
                fill="none"
                stroke={slice.color}
                strokeWidth={STROKE}
                strokeDasharray={`${dash} ${circumference - dash}`}
                strokeDashoffset={-o}
                opacity={active && active.key !== slice.key ? 0.3 : 1}
                onPress={() => toggle(slice.key)}
              />
            ))}
          </G>
        </Svg>
        <View pointerEvents="none" style={{ position: 'absolute', alignItems: 'center', width: SIZE - STROKE * 2 - 8 }}>
          <Text numberOfLines={1} style={{ fontSize: 12, color: c.textMuted }}>
            {active ? `${active.icon} ${active.label}` : 'Spent'}
          </Text>
          <Text numberOfLines={1} adjustsFontSizeToFit style={{ fontSize: 17, fontWeight: '700', color: c.text }}>
            {formatMoney(active ? active.minor : total, currency)}
          </Text>
          {active ? <Text style={{ fontSize: 12, color: c.textMuted }}>{pct(active.minor)} of spending</Text> : null}
        </View>
      </View>

      {/* Legend doubles as the table view: every slice with its amount and share. */}
      <View>
        {slices.map((s) => (
          <Pressable
            key={s.key}
            onPress={() => toggle(s.key)}
            accessibilityRole="button"
            accessibilityLabel={`${s.label}, ${formatMoney(s.minor, currency)}, ${pct(s.minor)}`}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              gap: spacing.sm,
              paddingVertical: 6,
              opacity: active && active.key !== s.key ? 0.5 : 1,
            }}>
            <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: s.color }} />
            <Text style={{ fontSize: 14 }}>{s.icon}</Text>
            <Text numberOfLines={1} style={{ flex: 1, fontSize: 14, color: c.text }}>
              {s.label}
            </Text>
            <Text style={{ fontSize: 14, color: c.text, fontVariant: ['tabular-nums'] }}>{formatMoney(s.minor, currency)}</Text>
            <Text style={{ width: 40, textAlign: 'right', fontSize: 13, color: c.textMuted, fontVariant: ['tabular-nums'] }}>
              {pct(s.minor)}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}
