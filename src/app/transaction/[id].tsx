import { router, useLocalSearchParams } from "expo-router";
import { useSQLiteContext } from "expo-sqlite";
import { useState } from "react";
import { Alert, View } from "react-native";

import {
  Body,
  Button,
  Card,
  Chip,
  ChipRow,
  Field,
  Label,
  Screen,
} from "@/components/ui";
import { useDbQuery } from "@/db/live";
import { accountLabel } from "@/features/accounts/repository";
import { categoryLook } from "@/features/categories/appearance";
import { REFUND_CATEGORY } from "@/features/categories/categorizer";
import { useLookups, type Lookups } from "@/features/transactions/components";
import {
  getTransaction,
  removeTransaction,
  saveTransaction,
  setStatus,
  type Transaction,
} from "@/features/transactions/repository";
import { parseSmsDate, toDateInput } from "@/lib/dates";
import {
  CURRENCIES,
  minorToInput,
  parseAmountToMinor,
  type Currency,
  type Direction,
} from "@/lib/money";
import { radius, spacing, useColors } from "@/theme";

type Kind = "expense" | "income" | "refund";

const KIND_CHIPS: { kind: Kind; label: string }[] = [
  { kind: "expense", label: "Expense" },
  { kind: "income", label: "Income" },
  { kind: "refund", label: "Refund" },
];

type Form = {
  direction: Direction;
  amount: string;
  currency: Currency;
  merchant: string;
  date: string;
  accountId: string | null;
  categoryId: string | null;
  note: string;
};

function initialForm(tx: Transaction | null): Form {
  if (!tx) {
    return {
      direction: "debit",
      amount: "",
      currency: "AED",
      merchant: "",
      date: toDateInput(Date.now()),
      accountId: null,
      categoryId: null,
      note: "",
    };
  }
  return {
    direction: tx.direction,
    amount: minorToInput(tx.amountMinor),
    currency: tx.currency,
    merchant: tx.merchant ?? "",
    date: toDateInput(tx.occurredAt),
    accountId: tx.accountId,
    categoryId: tx.categoryId,
    note: tx.note ?? "",
  };
}

export default function TransactionScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const lookups = useLookups();
  const tx = useDbQuery(
    (d) => (id === "new" ? Promise.resolve(null) : getTransaction(d, id)),
    id,
  );

  // undefined while loading; null for a new transaction.
  if (tx === undefined || !lookups) return <Screen>{null}</Screen>;
  return <TransactionForm tx={tx} lookups={lookups} />;
}

function TransactionForm({
  tx,
  lookups,
}: {
  tx: Transaction | null;
  lookups: Lookups;
}) {
  const db = useSQLiteContext();
  const c = useColors();
  const [form, setForm] = useState(() => initialForm(tx));

  const set = (patch: Partial<Form>) => setForm({ ...form, ...patch });
  const account = lookups.accounts.find((a) => a.id === form.accountId);
  const isRefund =
    form.direction === "credit" && form.categoryId === REFUND_CATEGORY;
  const kind: Kind = isRefund
    ? "refund"
    : form.direction === "credit"
      ? "income"
      : "expense";
  const categories = lookups.categories.filter(
    (cat) => cat.kind === kind && cat.id !== REFUND_CATEGORY,
  );
  const setKind = (next: Kind) => {
    if (next === kind) return;
    if (next === "refund")
      set({ direction: "credit", categoryId: REFUND_CATEGORY });
    else
      set({
        direction: next === "income" ? "credit" : "debit",
        categoryId: null,
      });
  };

  const save = async () => {
    const amountMinor = parseAmountToMinor(form.amount);
    if (!amountMinor)
      return Alert.alert("Enter an amount", "Use a number like 45.50.");
    const day = parseSmsDate(form.date);
    if (!day)
      return Alert.alert("Check the date", "Use the format YYYY-MM-DD.");
    // Keep the original time of day when only the date was edited.
    const occurredAt =
      tx && toDateInput(tx.occurredAt) === form.date
        ? tx.occurredAt
        : day + 12 * 60 * 60 * 1000;
    await saveTransaction(db, tx?.id ?? null, {
      amountMinor,
      currency: account?.currency ?? form.currency,
      direction: form.direction,
      merchant: form.merchant.trim() || null,
      note: form.note.trim() || null,
      occurredAt,
      accountId: form.accountId,
      categoryId: form.categoryId,
    });
    router.back();
  };

  const remove = () => {
    if (!tx) return;
    Alert.alert(
      "Delete transaction?",
      tx.source === "sms" ? "The SMS will not be imported again." : undefined,
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Delete",
          style: "destructive",
          onPress: async () => {
            await removeTransaction(db, tx);
            router.back();
          },
        },
      ],
    );
  };

  return (
    <Screen>
      <ChipRow>
        {KIND_CHIPS.map((k) => (
          <Chip
            key={k.kind}
            label={k.label}
            selected={kind === k.kind}
            onPress={() => setKind(k.kind)}
          />
        ))}
      </ChipRow>

      <Field
        label="Amount"
        value={form.amount}
        onChangeText={(amount) => set({ amount })}
        keyboardType="decimal-pad"
        placeholder="0.00"
      />

      <View style={{ gap: spacing.xs }}>
        <Label>Card / account</Label>
        <ChipRow>
          <Chip
            label="None"
            selected={!form.accountId}
            onPress={() => set({ accountId: null })}
          />
          {lookups.accounts.map((a) => (
            <Chip
              key={a.id}
              label={`${accountLabel(a)} (${a.currency})`}
              selected={form.accountId === a.id}
              onPress={() => set({ accountId: a.id, currency: a.currency })}
            />
          ))}
        </ChipRow>
      </View>

      {!account ? (
        <View style={{ gap: spacing.xs }}>
          <Label>Currency</Label>
          <ChipRow>
            {CURRENCIES.map((cur) => (
              <Chip
                key={cur}
                label={cur}
                selected={form.currency === cur}
                onPress={() => set({ currency: cur })}
              />
            ))}
          </ChipRow>
        </View>
      ) : null}

      {isRefund ? (
        <Body muted style={{ fontSize: 13 }}>
          A refund is money back from a shop. It lowers your spending and
          doesn’t count as income.
        </Body>
      ) : null}

      <Field
        label={kind === "income" ? "From" : "Merchant"}
        value={form.merchant}
        onChangeText={(merchant) => set({ merchant })}
        placeholder="e.g. Gazebo"
      />

      {isRefund ? null : (
        <View style={{ gap: spacing.xs }}>
          <Label>Category</Label>
          <ChipRow>
            {categories.map((cat) => (
              <Chip
                key={cat.id}
                label={`${categoryLook(cat.id).icon} ${cat.name}`}
                selected={form.categoryId === cat.id}
                onPress={() =>
                  set({
                    categoryId: form.categoryId === cat.id ? null : cat.id,
                  })
                }
              />
            ))}
          </ChipRow>
          {form.merchant.trim() && form.categoryId ? (
            <Body muted style={{ fontSize: 13 }}>
              Future transactions from “{form.merchant.trim()}” will get this
              category.
            </Body>
          ) : null}
        </View>
      )}

      <Field
        label="Date"
        value={form.date}
        onChangeText={(date) => set({ date })}
        placeholder="YYYY-MM-DD"
        autoCapitalize="none"
      />
      <Field
        label="Note"
        value={form.note}
        onChangeText={(note) => set({ note })}
        placeholder="Optional"
      />

      {tx?.smsBody ? (
        <Card>
          <Label>SMS from {tx.smsSender}</Label>
          <View
            style={{
              backgroundColor: c.surfaceAlt,
              borderRadius: radius.sm,
              padding: spacing.sm,
            }}
          >
            <Body style={{ fontSize: 14 }}>{tx.smsBody}</Body>
          </View>
        </Card>
      ) : null}

      <Button
        title={tx?.status === "pending" ? "Save and accept" : "Save"}
        onPress={save}
      />
      {tx?.status === "pending" ? (
        <Button
          title="Reject"
          variant="danger"
          onPress={async () => {
            await setStatus(db, [tx.id], "rejected");
            router.back();
          }}
        />
      ) : tx ? (
        <Button title="Delete" variant="danger" onPress={remove} />
      ) : null}
    </Screen>
  );
}
