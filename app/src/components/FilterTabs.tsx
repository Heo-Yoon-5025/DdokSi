import { Pressable, StyleSheet, Text, View } from 'react-native';
import { BillStatus } from '../types/bill';
import { colors, spacing } from '../theme';

/** 선택 가능한 필터 값. undefined 는 '전체'를 뜻한다. */
export type FilterValue = BillStatus | undefined;

type Props = {
  value: FilterValue;
  onChange: (next: FilterValue) => void;
};

/**
 * 탭 구성은 백엔드의 상태 4종을 그대로 노출한다.
 * 특히 '대안반영'은 전체의 약 21%로 '통과'보다 훨씬 많아, 숨기면 오히려 이해를 방해한다.
 */
const TABS: { label: string; value: FilterValue }[] = [
  { label: '전체', value: undefined },
  { label: '논의중', value: 'PENDING' },
  { label: '통과', value: 'PASSED' },
  { label: '대안반영', value: 'MERGED' },
  { label: '폐기', value: 'DISCARDED' },
];

/**
 * 상태별 필터 탭.
 *
 * 가로 ScrollView 를 쓰지 않는다. flex 컬럼 부모 안에서 목록과 공간을 다투다가
 * 높이가 0 근처로 짜부라져 탭 글자가 보이지 않는 문제가 있었다.
 * 탭이 5개뿐이라 좁은 화면에서는 줄바꿈으로 내려가는 편이 단순하고 안전하다.
 */
export default function FilterTabs({ value, onChange }: Props) {
  return (
    <View style={styles.row}>
      {TABS.map((tab) => {
        // 현재 선택된 탭인지 판정 (전체 탭은 value 가 undefined 일 때 선택됨)
        const selected = tab.value === value;

        return (
          <Pressable
            key={tab.label}
            onPress={() => onChange(tab.value)}
            style={[styles.tab, selected && styles.tabSelected]}
          >
            <Text style={[styles.label, selected && styles.labelSelected]}>{tab.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.md,
  },
  tab: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: colors.border,
  },
  tabSelected: {
    backgroundColor: colors.accentSoft,
    borderColor: colors.accentSoft,
  },
  label: {
    fontSize: 13,
    color: colors.textMuted,
  },
  labelSelected: {
    color: colors.accent,
    fontWeight: '600',
  },
});
