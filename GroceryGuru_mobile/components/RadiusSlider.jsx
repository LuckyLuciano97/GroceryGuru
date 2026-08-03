import { useRef, useState } from 'react';
import { View, Text, PanResponder, StyleSheet } from 'react-native';

/**
 * Drag-to-set radius control. Built on PanResponder rather than a slider
 * package so it behaves the same on web and native without another dependency.
 *
 * onChange fires while dragging (cheap, just updates the label); onCommit fires
 * once on release, so the stores are not refetched on every frame.
 */
export default function RadiusSlider({ value, min = 1, max = 50, onChange, onCommit }) {
  const [width, setWidth] = useState(0);
  const widthRef = useRef(0);
  const latest = useRef(value);

  const valueAt = (x) => {
    const w = widthRef.current;
    if (!w) return value;
    const ratio = Math.max(0, Math.min(1, x / w));
    return Math.round(min + ratio * (max - min));
  };

  const responder = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (e) => {
        const v = valueAt(e.nativeEvent.locationX);
        latest.current = v;
        onChange(v);
      },
      onPanResponderMove: (e, gesture) => {
        // locationX is relative to the thumb once it grabs, so track moveX
        // against the bar's own left edge instead.
        const x = gesture.moveX - offsetRef.current;
        const v = valueAt(x);
        if (v !== latest.current) {
          latest.current = v;
          onChange(v);
        }
      },
      onPanResponderRelease: () => onCommit(latest.current),
      onPanResponderTerminate: () => onCommit(latest.current),
    })
  ).current;

  const offsetRef = useRef(0);
  const pct = (value - min) / (max - min);

  return (
    <View style={styles.wrap}>
      <View style={styles.labelRow}>
        <Text style={styles.label}>Search radius</Text>
        <Text style={styles.value}>{value} km</Text>
      </View>
      <View
        style={styles.hit}
        onLayout={(e) => {
          const { width: w, x } = e.nativeEvent.layout;
          setWidth(w);
          widthRef.current = w;
          offsetRef.current = x;
        }}
        {...responder.panHandlers}
      >
        <View style={styles.track} />
        <View style={[styles.fill, { width: Math.max(0, pct * width) }]} />
        <View style={[styles.thumb, { left: Math.max(0, pct * width - 11) }]} />
      </View>
      <View style={styles.scaleRow}>
        <Text style={styles.scale}>{min} km</Text>
        <Text style={styles.scale}>{max} km</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { paddingHorizontal: 16, paddingTop: 4, paddingBottom: 10 },
  labelRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10 },
  label: { fontSize: 13, color: '#666', fontWeight: '600' },
  value: { fontSize: 15, color: '#5B4FE8', fontWeight: '800' },
  hit: { height: 28, justifyContent: 'center' },
  track: { height: 4, borderRadius: 2, backgroundColor: '#e3e4ef' },
  fill: { position: 'absolute', height: 4, borderRadius: 2, backgroundColor: '#5B4FE8' },
  thumb: {
    position: 'absolute', width: 22, height: 22, borderRadius: 11,
    backgroundColor: '#fff', borderWidth: 3, borderColor: '#5B4FE8',
    shadowColor: '#000', shadowOpacity: 0.2, shadowRadius: 3, shadowOffset: { width: 0, height: 1 },
    elevation: 3,
  },
  scaleRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 6 },
  scale: { fontSize: 11, color: '#aaa' },
});
