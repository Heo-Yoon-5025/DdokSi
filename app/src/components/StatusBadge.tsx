import { StyleSheet, Text, View } from 'react-native';
import { BillStatus } from '../types/bill';
import { spacing, statusColors } from '../theme';

type Props = {
  status: BillStatus;
  /** 표시 문구는 서버가 내려준 라벨을 쓴다. 앱이 따로 정의하면 문구가 갈라진다. */
  label: string;
};

/** 법안 처리 상태를 작은 배지로 표시한다. */
export default function StatusBadge({ status, label }: Props) {
  // 서버가 새로운 상태를 추가해도 앱이 깨지지 않도록 기본값을 둔다
  const palette = statusColors[status] ?? statusColors.UNKNOWN;

  return (
    <View style={[styles.badge, { backgroundColor: palette.background }]}>
      <Text style={[styles.label, { color: palette.text }]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    alignSelf: 'flex-start',
    paddingHorizontal: spacing.sm,
    paddingVertical: 3,
    borderRadius: 4,
  },
  label: {
    fontSize: 11,
    fontWeight: '600',
  },
});
