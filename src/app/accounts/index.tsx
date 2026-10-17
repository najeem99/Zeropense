import { router } from "expo-router";
import { useSQLiteContext } from "expo-sqlite";
import { useState } from "react";
import { Alert, View } from "react-native";

import { Body, Button, Card, Field, Label, Row, Screen } from "@/components/ui";
import { useDbQuery } from "@/db/live";
import {
  ACCOUNT_KIND_LABELS,
  accountLabel,
  deleteAccount,
  listAccounts,
  listDetectedCards,
  renameAccount,
  type Account,
  type AccountKind,
} from "@/features/accounts/repository";
import { formatDate } from "@/lib/dates";
import { spacing } from "@/theme";

const KIND_ORDER: AccountKind[] = ["credit_card", "debit_card", "bank"];

export default function Accounts() {
  const db = useSQLiteContext();
  const data = useDbQuery(async (d) => {
    const [accounts, detected] = await Promise.all([
      listAccounts(d),
      listDetectedCards(d),
    ]);
    return { accounts, detected };
  });
  if (!data) return <Screen>{null}</Screen>;

  const confirmDelete = (a: Account) =>
    Alert.alert(
      `Delete ${accountLabel(a)}?`,
      "Its transactions are kept but no longer linked to it.",
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Delete",
          style: "destructive",
          onPress: () => deleteAccount(db, a.id),
        },
      ],
    );

  return (
    <Screen>
      {data.detected.length > 0 ? (
        <Card>
          <Label>Found in your SMS</Label>
          <Body muted style={{ fontSize: 14 }}>
            These card or account numbers appear in your bank messages. Add them
            so you can see which card each payment was made with.
          </Body>
          {data.detected.map((d) => (
            <Row
              key={`${d.digits}-${d.currency}`}
              title={`Ending ••${d.digits}`}
              subtitle={[
                `${d.count} transaction${d.count === 1 ? "" : "s"}`,
                d.currency,
                d.lastSender,
                `last ${formatDate(d.lastAt)}`,
              ]
                .filter(Boolean)
                .join(" · ")}
              right={
                <Button
                  title="Add"
                  variant="secondary"
                  onPress={() =>
                    router.push({
                      pathname: "/accounts/new",
                      params: {
                        digits: d.digits,
                        currency: d.currency,
                        sender: d.lastSender ?? "",
                      },
                    })
                  }
                />
              }
            />
          ))}
        </Card>
      ) : null}

      {KIND_ORDER.map((kind) => {
        const items = data.accounts.filter((a) => a.kind === kind);
        if (items.length === 0) return null;
        return (
          <Card key={kind}>
            <Label>{ACCOUNT_KIND_LABELS[kind]}s</Label>
            {items.map((a) => (
              <AccountRow
                key={a.id}
                account={a}
                onDelete={() => confirmDelete(a)}
              />
            ))}
          </Card>
        );
      })}

      {data.accounts.length === 0 && data.detected.length === 0 ? (
        <Body muted>
          No cards or accounts yet. Add one, or scan your SMS and the cards will
          be found for you.
        </Body>
      ) : null}

      <Button
        title="Add card or account"
        onPress={() => router.push("/accounts/new")}
      />
    </Screen>
  );
}

/** A saved card; "Rename" swaps the row for a name field in place. */
function AccountRow({
  account: a,
  onDelete,
}: {
  account: Account;
  onDelete: () => void;
}) {
  const db = useSQLiteContext();
  const [draft, setDraft] = useState<string | null>(null);

  if (draft === null) {
    return (
      <Row
        title={accountLabel(a)}
        subtitle={a.currency}
        right={
          <View style={{ flexDirection: "row" }}>
            <Button
              title="Rename"
              variant="ghost"
              onPress={() => setDraft(a.name)}
            />
            <Button title="Delete" variant="ghost" onPress={onDelete} />
          </View>
        }
      />
    );
  }

  const save = async () => {
    if (draft.trim()) await renameAccount(db, a.id, draft);
    setDraft(null);
  };
  return (
    <View style={{ gap: spacing.sm, paddingVertical: spacing.sm }}>
      <Field
        label={a.smsHint ? `Name for card ••${a.smsHint}` : "Name"}
        value={draft}
        onChangeText={setDraft}
        onSubmitEditing={save}
        autoFocus
        returnKeyType="done"
        placeholder="e.g. ENBD Platinum"
      />
      <View style={{ flexDirection: "row", gap: spacing.sm }}>
        <Button title="Save" onPress={save} disabled={!draft.trim()} />
        <Button title="Cancel" variant="ghost" onPress={() => setDraft(null)} />
      </View>
    </View>
  );
}
