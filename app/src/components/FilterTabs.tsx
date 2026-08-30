import { Pressable, StyleSheet, Text, View } from 'react-native';
import { BillStatus } from '../types/bill';
import { colors, spacing } from '../theme';

/** 화면에서 선택 가능한 필터 값. undefined는 '전체'를 뜻한다. */
export type FilterValue = BillStatus | undefined;

type Props = {
  value: FilterValue;
  onChange: (next: FilterValue) => void;
};

const TABS: { label: string; value: FilterValue }[] = [
  { label: '전체', value: undefined },
  { label: '논의중', value: 'PENDING' },
  { label: '통과', value: 'PASSED' },
];

/** 상태별 필터 탭. 선택된 항목만 배경색으로 구분한다. */
export default function FilterTabs({ value, onChange }: Props) {
  return (
    <View style={styles.row}>
      {TABS.map((tab) => {
        // 현재 선택된 탭인지 판정 (전체 탭은 value가 undefined일 때 선택됨)
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
