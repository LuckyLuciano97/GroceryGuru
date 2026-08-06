import { Platform } from 'react-native';

/**
 * Desktop presentation for the web build: on wide screens the app renders as a
 * centered phone-width column on a dark background, so it reads as a
 * mobile-first design rather than a phone layout stretched across the screen.
 *
 * Width-only on purpose. The earlier phone-bezel attempt also pinned #root's
 * height and hid overflow, which fights react-native-web's viewport-based
 * layout: content rendered compressed, the bottom was clipped and controls
 * outside the clipped box went dead. Constraining width and centering leaves
 * the vertical layout and scrolling exactly as react-native-web expects.
 */
const CSS = `
  @media (min-width: 700px) {
    /* The gradient sits on body, which is height:100% - at fractional viewport
       heights (Windows 125% display scaling) rounding leaves a strip below body
       where the page canvas shows through white. Painting html dark makes any
       such strip invisible. */
    html {
      background: #0f1332;
    }
    body {
      background:
        radial-gradient(1100px 560px at 15% 8%, #2b3a9e 0%, transparent 60%),
        linear-gradient(155deg, #161c4a 0%, #0f1332 100%);
    }
    #root {
      max-width: 480px;
      margin: 0 auto;
      box-shadow: 0 0 60px rgba(0, 0, 0, 0.5);
    }
  }
`;

export function installWebLayout() {
  if (Platform.OS !== 'web' || typeof document === 'undefined') return;
  if (document.getElementById('gg-web-layout')) return;
  const style = document.createElement('style');
  style.id = 'gg-web-layout';
  style.textContent = CSS;
  document.head.appendChild(style);
}
