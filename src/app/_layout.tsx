import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import { SQLiteProvider } from 'expo-sqlite';
import { useColorScheme } from 'react-native';

import { migrate } from '@/db/migrations';

export default function RootLayout() {
  const scheme = useColorScheme();
  return (
    <SQLiteProvider databaseName="expense.db" onInit={migrate}>
      <ThemeProvider value={scheme === 'dark' ? DarkTheme : DefaultTheme}>
        <Stack screenOptions={{ headerBackButtonDisplayMode: 'minimal' }}>
          <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
          <Stack.Screen name="transaction/[id]" options={{ title: 'Transaction', presentation: 'modal' }} />
          <Stack.Screen name="import-sms" options={{ title: 'Paste an SMS' }} />
          <Stack.Screen name="sms-setup" options={{ title: 'Set up bank SMS' }} />
          <Stack.Screen name="accounts/index" options={{ title: 'Cards & accounts' }} />
          <Stack.Screen name="accounts/new" options={{ title: 'Add card or account' }} />
          <Stack.Screen name="categories" options={{ title: 'Categories' }} />
          <Stack.Screen name="parsers/index" options={{ title: 'SMS configuration' }} />
          <Stack.Screen name="parsers/new" options={{ title: 'Set up SMS format' }} />
        </Stack>
      </ThemeProvider>
    </SQLiteProvider>
  );
}
