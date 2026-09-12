import { StyleSheet, Text, View } from 'react-native';
import { BillSummary } from '../types/bill';
import { colors, spacing } from '../theme';
import StatusBadge from './StatusBadge';

type Props = {
  bill: BillSummary;
};

/** 제안일(ISO 날짜)을 'YYYY.MM.DD' 형태로 바꾼다. */
function formatDate(isoDate: string | null): string | null {
  return isoDate ? isoDate.replace(/-/g, '.') : null;
}

/**
 * 목록의 법안 한 건.
 * 카드 테두리나 그림자를 쓰지 않고 여백과 구분선만으로 항목을 나눈다.
 */
export default function BillCard({ bill }: Props) {
  // 값이 없는 항목은 표시하지 않는다.
  // 갓 발의된 법안은 위원회 회부 전이라 committeeName 이 비어 있다 (실데이터 기준 약 0.4%).
  const meta = [bill.committeeName, bill.proposerSummary, formatDate(bill.proposedDate)]
    .filter(Boolean)
    .join(' · ');

  return (
    <View style={styles.container}>
      <StatusBadge status={bill.status} label={bill.statusLabel} />

      <Text style={styles.title}>{bill.title}</Text>

      {/* AI 요약은 분석 파이프라인이 생기기 전까지 null 이다 */}
      {bill.summary ? <Text style={styles.summary}>{bill.summary}</Text> : null}

      {meta ? <Text style={styles.meta}>{meta}</Text> : null}
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
