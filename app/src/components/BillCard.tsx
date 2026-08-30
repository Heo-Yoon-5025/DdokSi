import { StyleSheet, Text, View } from 'react-native';
import { Bill } from '../types/bill';
import { colors, spacing } from '../theme';
import StatusBadge from './StatusBadge';

type Props = {
  bill: Bill;
};

/** 제안일(ISO 날짜)을 'YYYY.MM.DD' 형태로 바꾼다. */
function formatDate(isoDate: string): string {
  return isoDate.replace(/-/g, '.');
}

/**
 * 목록의 법안 한 건을 표시한다.
 * 카드 테두리나 그림자를 쓰지 않고 여백과 구분선만으로 항목을 나눈다.
 */
export default function BillCard({ bill }: Props) {
  return (
    <View style={styles.container}>
      <StatusBadge status={bill.status} />

      <Text style={styles.title}>{bill.title}</Text>

      {/* AI 요약은 생성에 실패할 수 있으므로 값이 있을 때만 렌더링한다 */}
      {bill.summary.length > 0 && <Text style={styles.summary}>{bill.summary}</Text>}

      <Text style={styles.meta}>
        {bill.committee} · {bill.proposer} · {formatDate(bill.proposedDate)}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    paddingVertical: spacing.lg,
    paddingHorizontal: spacing.md,
    gap: spacing.sm,
  },
  title: {
    fontSize: 16,
    fontWeight: '600',
    color: colors.text,
    lineHeight: 24,
  },
  summary: {
    fontSize: 14,
    color: colors.textMuted,
    lineHeight: 22,
  },
  meta: {
    fontSize: 12,
    color: colors.textMuted,
    marginTop: spacing.xs,
  },
});
