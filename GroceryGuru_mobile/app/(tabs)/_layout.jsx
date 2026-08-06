import { Tabs, Redirect } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { useAuth } from '../../services/AuthContext';
import { useI18n } from '../../services/i18n';
import { ActivityIndicator, Platform, View } from 'react-native';
import { colors } from '../../services/theme';

export default function TabsLayout() {
  const { user, loading } = useAuth();
  const { t } = useI18n();

  if (loading) {
    return (
      <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center' }}>
        <ActivityIndicator size="large" color={colors.primary} />
      </View>
    );
  }

  if (!user) return <Redirect href="/login" />;

  return (
    <Tabs
      screenOptions={{
        headerStyle: { backgroundColor: colors.bg },
        headerTintColor: colors.text,
        headerTitleStyle: { fontWeight: '800', fontSize: 22, color: colors.text },
        headerShadowVisible: false,
        tabBarActiveTintColor: colors.primary,
        tabBarInactiveTintColor: '#B6B6C6',
        tabBarStyle: {
          backgroundColor: colors.bg,
          borderTopWidth: 1,
          borderTopColor: colors.border,
          // Web sizing is exact: the item contributes 10px of its own padding
          // and a fixed 28px icon block, and the label needs 14px - the label
          // is the flexible child, so any shortfall crushes it first. The old
          // 62/6/8 left it 9px tall, which is why the labels rendered cut off.
          // Native keeps the taller bar for the phone gesture area.
          height: Platform.OS === 'web' ? 60 : 62,
          paddingBottom: Platform.OS === 'web' ? 3 : 8,
          paddingTop: Platform.OS === 'web' ? 3 : 6,
        },
        tabBarLabelStyle: {
          fontSize: 11,
          fontWeight: '600',
          // explicit so the flex squeeze can't shrink the text box
          ...(Platform.OS === 'web' ? { lineHeight: 14 } : null),
        },
      }}
    >
      <Tabs.Screen
        name="lists"
        options={{
          title: t('myLists'),
          headerTitle: 'GroceryGuru',
          tabBarIcon: ({ color, size }) => <Ionicons name="list" size={size} color={color} />,
        }}
      />
      <Tabs.Screen
        name="cards"
        options={{
          title: t('cards'),
          tabBarIcon: ({ color, size }) => <Ionicons name="card-outline" size={size} color={color} />,
        }}
      />
      <Tabs.Screen
        name="stores"
        options={{
          title: t('nearbyStores'),
          tabBarIcon: ({ color, size }) => <Ionicons name="location-outline" size={size} color={color} />,
        }}
      />
      <Tabs.Screen
        name="profile"
        options={{
          title: t('profile'),
          tabBarIcon: ({ color, size }) => <Ionicons name="person-circle-outline" size={size} color={color} />,
        }}
      />
    </Tabs>
  );
}
