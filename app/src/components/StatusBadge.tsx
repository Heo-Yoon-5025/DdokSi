import { StyleSheet, Text, View } from 'react-native';
import { BillStatus, STATUS_LABEL } from '../types/bill';
import { colors, spacing } from '../theme';

type Props = {
  status: BillStatus;
};

/** 법안 처리 상태를 작은 배지로 표시한다. */
export default function StatusBadge({ status }: Props) {
  // 상태별로 배경/글자색을 다르게 준다
  const isPassed = status === 'PASSED';
  const backgroundColor = isPassed ? colors.passedBackground : colors.pendingBackground;
  const color = isPassed ? colors.passedText : colors.pendingText;

  return (
    <View style={[styles.badge, { backgroundColor }]}>
      <Text style={[styles.label, { color }]}>{STATUS_LABEL[status]}</Text>
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
