import { Alert, Platform } from 'react-native';

/**
 * react-native-web does not implement Alert, so every Alert.alert call is a
 * silent no-op in a browser. Confirmations built on it did nothing at all -
 * logging out and deleting a list looked like dead buttons - and error alerts
 * never appeared. These wrappers fall back to the browser dialogs on web.
 */

export function confirm(title, message, onConfirm, options = {}) {
  const {
    confirmLabel = 'OK',
    cancelLabel = 'Cancel',
    destructive = false,
  } = options;

  if (Platform.OS === 'web') {
    const text = message ? `${title}\n\n${message}` : title;
    // eslint-disable-next-line no-alert
    if (typeof window !== 'undefined' && window.confirm(text)) onConfirm();
    return;
  }

  Alert.alert(title, message, [
    { text: cancelLabel, style: 'cancel' },
    { text: confirmLabel, style: destructive ? 'destructive' : 'default', onPress: onConfirm },
  ]);
}

export function notify(title, message) {
  if (Platform.OS === 'web') {
    const text = message ? `${title}\n\n${message}` : title;
    // eslint-disable-next-line no-alert
    if (typeof window !== 'undefined') window.alert(text);
    return;
  }
  Alert.alert(title, message);
}
