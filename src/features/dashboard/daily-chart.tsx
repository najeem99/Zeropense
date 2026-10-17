import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';

import { seriesColor } from '@/features/categories/appearance';
import { formatMoney, type Currency } from '@/lib/money';
import { spacing, useColors, useScheme } from '@/theme';

import { dayAt, type DailySpending } from './daily';

const HEIGHT = 120;

const dayLabel = (ts: number) => new Date(ts).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });

/** Money out per day as thin bars. Tap a bar for that day; tap it again to go back to the average. */
export function DailyChart({ data, from, currency }: { data: DailySpending; from: number; currency: Currency }) {
  const c = useColors();
  const color = seriesColor(useScheme());
  const [selected, setSelected] = useState<number | null>(null);

  if (!data.peak) return null;
  const max = data.peak.minor;
  const count = data.days.length;
  // A few evenly spaced date labels under the bars.
  const ticks = [0, Math.round((count - 1) / 3), Math.round(((count - 1) * 2) / 3), count - 1];

  return (
    <View style={{ gap: spacing.sm, marginTop: spacing.sm }}>
      <Text style={{ fontSize: 13, fontWeight: '600', color: c.textMuted, textTransform: 'uppercase', letterSpacing: 0.4 }}>
        Spent per day
      </Text>
      <View>
        <Text style={{ fontSize: 17, fontWeight: '700', color: c.text, fontVariant: ['tabular-nums'] }}>
          {formatMoney(selected === null ? data.averageMinor : data.days[selected], currency)}
        </Text>
        <Text style={{ fontSize: 12, color: c.textMuted }}>
          {selected === null
            ? `Average per day · highest ${formatMoney(max, currency)} on ${dayLabel(dayAt(from, data.peak.index))}`
            : `Spent on ${dayLabel(dayAt(from, selected))}`}
        </Text>
      </View>

      <View
        accessibilityLabel={`Spending per day, average ${formatMoney(data.averageMinor, currency)}`}
        style={{ height: HEIGHT, flexDirection: 'row', alignItems: 'flex-end', borderBottomWidth: 1, borderColor: c.border }}>
        {data.days.map((minor, i) => (
          <Pressable
            key={i}
            onPress={() => setSelected(selected === i ? null : i)}
            accessibilityLabel={`${dayLabel(dayAt(from, i))}: ${formatMoney(minor, currency)}`}
            style={{ flex: 1, height: '100%', justifyContent: 'flex-end', paddingHorizontal: 1 }}>
            {minor > 0 ? (
              <View
                style={{
                  height: Math.max(2, (minor / max) * HEIGHT),
                  backgroundColor: color,
                  borderTopLeftRadius: 4,
                  borderTopRightRadius: 4,
                  opacity: selected !== null && selected !== i ? 0.35 : 1,
                }}
              />
            ) : null}
          </Pressable>
        ))}
      </View>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        {ticks.map((t, k) => (
          <Text key={k} style={{ fontSize: 11, color: c.textMuted }}>
            {dayLabel(dayAt(from, t))}
          </Text>
        ))}
      </View>
    </View>
  );
}
