import { useSQLiteContext } from 'expo-sqlite';
import { useState } from 'react';
import { Alert } from 'react-native';

import { Body, Button, Card, Chip, ChipRow, Field, Label, Row, Screen } from '@/components/ui';
import { useDbQuery } from '@/db/live';
import {
  createCategory,
  deleteCategory,
  deleteRule,
  listCategories,
  listRules,
  type Category,
  type CategoryKind,
} from '@/features/categories/repository';

export default function Categories() {
  const db = useSQLiteContext();
  const data = useDbQuery(async (d) => {
    const [categories, rules] = await Promise.all([listCategories(d), listRules(d)]);
    return { categories, rules };
  });
  const [name, setName] = useState('');
  const [kind, setKind] = useState<CategoryKind>('expense');

  if (!data) return <Screen>{null}</Screen>;
  const nameOf = new Map(data.categories.map((c) => [c.id, c.name]));

  const confirmDelete = (cat: Category) =>
    Alert.alert(`Delete ${cat.name}?`, 'Its transactions become uncategorised.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => deleteCategory(db, cat.id) },
    ]);

  return (
    <Screen>
      <Card>
        <Label>Remembered merchants</Label>
        {data.rules.length === 0 ? (
          <Body muted>When you accept or edit a transaction with a category, the merchant is remembered here.</Body>
        ) : (
          data.rules.map((r) => (
            <Row
              key={r.merchantKey}
              title={r.merchantKey}
              subtitle={nameOf.get(r.categoryId) ?? r.categoryId}
              right={<Button title="Forget" variant="ghost" onPress={() => deleteRule(db, r.merchantKey)} />}
            />
          ))
        )}
      </Card>

      {(['expense', 'income'] as const).map((k) => (
        <Card key={k}>
          <Label>{k === 'expense' ? 'Expense categories' : 'Income categories'}</Label>
          {data.categories
            .filter((c) => c.kind === k)
            .map((c) => (
              <Row key={c.id} title={c.name} right={<Button title="Delete" variant="ghost" onPress={() => confirmDelete(c)} />} />
            ))}
        </Card>
      ))}

      <Card>
        <Label>Add category</Label>
        <Field label="Name" value={name} onChangeText={setName} placeholder="e.g. Kids" />
        <ChipRow>
          <Chip label="Expense" selected={kind === 'expense'} onPress={() => setKind('expense')} />
          <Chip label="Income" selected={kind === 'income'} onPress={() => setKind('income')} />
        </ChipRow>
        <Button
          title="Add category"
          disabled={!name.trim()}
          onPress={async () => {
            await createCategory(db, name, kind);
            setName('');
          }}
        />
      </Card>
    </Screen>
  );
}
