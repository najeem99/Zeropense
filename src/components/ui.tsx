import type { ReactNode } from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  type StyleProp,
  type TextInputProps,
  type TextStyle,
  type ViewStyle,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { formatMoney, type Currency, type Direction } from '@/lib/money';
import { radius, spacing, useColors } from '@/theme';

/** Scrollable page. `title` renders a large heading for tab screens, which have no header. */
export function Screen({ title, children, footer }: { title?: string; children: ReactNode; footer?: ReactNode }) {
  const c = useColors();
  return (
    <SafeAreaView edges={title ? ['top'] : []} style={{ flex: 1, backgroundColor: c.background }}>
      <ScrollView
        contentInsetAdjustmentBehavior="automatic"
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{ padding: spacing.lg, gap: spacing.md, paddingBottom: spacing.xl * 2 }}>
        {title ? <Text style={[styles.screenTitle, { color: c.text }]}>{title}</Text> : null}
        {children}
      </ScrollView>
      {footer}
    </SafeAreaView>
  );
}

export function Card({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  const c = useColors();
  return <View style={[styles.card, { backgroundColor: c.surface, borderColor: c.border }, style]}>{children}</View>;
}

export function Label({ children, style }: { children: ReactNode; style?: StyleProp<TextStyle> }) {
  const c = useColors();
  return <Text style={[styles.label, { color: c.textMuted }, style]}>{children}</Text>;
}

export function Body({
  children,
  muted,
  style,
  numberOfLines,
}: {
  children: ReactNode;
  muted?: boolean;
  style?: StyleProp<TextStyle>;
  numberOfLines?: number;
}) {
  const c = useColors();
  return (
    <Text numberOfLines={numberOfLines} style={[styles.body, { color: muted ? c.textMuted : c.text }, style]}>
      {children}
    </Text>
  );
}

type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'ghost';

export function Button({
  title,
  onPress,
  variant = 'primary',
  disabled,
  style,
}: {
  title: string;
  onPress: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
}) {
  const c = useColors();
  const bg = { primary: c.primary, secondary: c.surfaceAlt, danger: c.surfaceAlt, ghost: 'transparent' }[variant];
  const fg = { primary: c.onPrimary, secondary: c.text, danger: c.expense, ghost: c.primary }[variant];
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      disabled={disabled}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: bg, opacity: disabled ? 0.4 : pressed ? 0.75 : 1 },
        style,
      ]}>
      <Text style={[styles.buttonText, { color: fg }]}>{title}</Text>
    </Pressable>
  );
}

export function Chip({ label, selected, onPress }: { label: string; selected?: boolean; onPress?: () => void }) {
  const c = useColors();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected }}
      onPress={onPress}
      style={[
        styles.chip,
        { backgroundColor: selected ? c.primary : c.surfaceAlt, borderColor: selected ? c.primary : c.border },
      ]}>
      <Text style={{ color: selected ? c.onPrimary : c.text, fontSize: 14 }}>{label}</Text>
    </Pressable>
  );
}

export function ChipRow({ children }: { children: ReactNode }) {
  return <View style={styles.chipRow}>{children}</View>;
}

export function Field({ label, ...props }: TextInputProps & { label: string }) {
  const c = useColors();
  return (
    <View style={{ gap: spacing.xs }}>
      <Label>{label}</Label>
      <TextInput
        placeholderTextColor={c.textMuted}
        {...props}
        style={[
          styles.input,
          { color: c.text, backgroundColor: c.surface, borderColor: c.border },
          props.multiline && { minHeight: 110, textAlignVertical: 'top' },
          props.style,
        ]}
      />
    </View>
  );
}

export function Amount({
  minor,
  currency,
  direction,
  style,
}: {
  minor: number;
  currency: Currency;
  direction?: Direction;
  style?: StyleProp<TextStyle>;
}) {
  const c = useColors();
  const signed = direction === 'debit' ? -minor : minor;
  const color = direction === 'credit' ? c.income : c.text;
  return (
    <Text style={[styles.amount, { color }, style]}>{formatMoney(signed, currency, { signed: direction === 'credit' })}</Text>
  );
}

export function Row({
  title,
  subtitle,
  right,
  onPress,
}: {
  title: string;
  subtitle?: string;
  right?: ReactNode;
  onPress?: () => void;
}) {
  const c = useColors();
  return (
    <Pressable
      onPress={onPress}
      disabled={!onPress}
      style={({ pressed }) => [styles.row, { borderColor: c.border, opacity: pressed ? 0.6 : 1 }]}>
      <View style={{ flex: 1, gap: 2 }}>
        <Body numberOfLines={1}>{title}</Body>
        {subtitle ? (
          <Body muted numberOfLines={1} style={{ fontSize: 13 }}>
            {subtitle}
          </Body>
        ) : null}
      </View>
      {right}
    </Pressable>
  );
}

export function Empty({ title, message, children }: { title: string; message?: string; children?: ReactNode }) {
  return (
    <Card style={{ alignItems: 'center', paddingVertical: spacing.xl, gap: spacing.sm }}>
      <Body style={{ fontWeight: '600' }}>{title}</Body>
      {message ? <Body muted style={{ textAlign: 'center' }}>{message}</Body> : null}
      {children}
    </Card>
  );
}

export function MonthSwitcher({ label, onPrev, onNext }: { label: string; onPrev: () => void; onNext: () => void }) {
  return (
    <View style={styles.monthSwitcher}>
      <Button title="‹" variant="ghost" onPress={onPrev} />
      <Body style={{ fontWeight: '600', fontSize: 17 }}>{label}</Body>
      <Button title="›" variant="ghost" onPress={onNext} />
    </View>
  );
}

const styles = StyleSheet.create({
  screenTitle: { fontSize: 32, fontWeight: '700', marginBottom: spacing.xs },
  card: { borderRadius: radius.lg, borderWidth: StyleSheet.hairlineWidth, padding: spacing.lg, gap: spacing.sm },
  label: { fontSize: 13, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.4 },
  body: { fontSize: 16 },
  button: {
    minHeight: 44,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { fontSize: 16, fontWeight: '600' },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.pill,
    borderWidth: StyleSheet.hairlineWidth,
  },
  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  input: {
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
    fontSize: 16,
  },
  amount: { fontSize: 16, fontWeight: '600', fontVariant: ['tabular-nums'] },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingVertical: spacing.md,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  monthSwitcher: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
});
