import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, StyleSheet, Text, View } from 'react-native';
import { fetchBills } from '../api/bills';
import { Bill } from '../types/bill';
import BillCard from '../components/BillCard';
import FilterTabs, { FilterValue } from '../components/FilterTabs';
import { CONTENT_MAX_WIDTH, colors, spacing } from '../theme';

/**
 * 메인 화면.
 * 법안 목록을 조회해서 보여주고, 상태별 필터를 제공한다. (로그인 없음)
 */
export default function HomeScreen() {
  const [bills, setBills] = useState<Bill[]>([]);
  const [filter, setFilter] = useState<FilterValue>(undefined);
  const [loading, setLoading] = useState(true);

  /** 현재 필터 기준으로 목록을 다시 불러온다. */
  const loadBills = useCallback(async (status: FilterValue) => {
    setLoading(true);
    try {
      const result = await fetchBills(status);
      setBills(result);
    } finally {
      // 실패하더라도 로딩 표시는 반드시 해제해서 화면이 멈춘 것처럼 보이지 않게 한다
      setLoading(false);
    }
  }, []);

  // 최초 진입 시, 그리고 필터가 바뀔 때마다 목록을 다시 조회한다
  useEffect(() => {
    loadBills(filter);
  }, [filter, loadBills]);

  return (
    <View style={styles.screen}>
      <View style={styles.content}>
        {/* 헤더 */}
        <View style={styles.header}>
          <Text style={styles.logo}>똑시</Text>
          <Text style={styles.tagline}>국회에서 지금 무슨 일이 있었는지</Text>
        </View>

        {/* 데이터 출처를 오해하지 않도록 목업 상태를 명시한다 (실제 API 연동 시 제거) */}
        <View style={styles.notice}>
          <Text style={styles.noticeText}>목업 데이터입니다. 실제 법안 정보가 아닙니다.</Text>
        </View>

        <FilterTabs value={filter} onChange={setFilter} />

        {/* 로딩 중에는 스피너만, 완료되면 목록을 보여준다 */}
        {loading ? (
          <View style={styles.loading}>
            <ActivityIndicator color={colors.textMuted} />
          </View>
        ) : (
          <FlatList
            data={bills}
            keyExtractor={(item) => item.id}
            renderItem={({ item }) => <BillCard bill={item} />}
            ItemSeparatorComponent={() => <View style={styles.separator} />}
            ListEmptyComponent={<Text style={styles.empty}>해당하는 법안이 없습니다.</Text>}
            contentContainerStyle={styles.listContent}
          />
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.background,
    // 웹에서 넓은 화면일 때 콘텐츠를 가운데로 모은다
    alignItems: 'center',
  },
  content: {
    flex: 1,
    width: '100%',
    maxWidth: CONTENT_MAX_WIDTH,
  },
  header: {
    paddingHorizontal: spacing.md,
    paddingTop: spacing.xl,
    paddingBottom: spacing.lg,
    gap: spacing.xs,
  },
  logo: {
    fontSize: 28,
    fontWeight: '700',
    color: colors.text,
    letterSpacing: -0.5,
  },
  tagline: {
    fontSize: 14,
    color: colors.textMuted,
  },
  notice: {
    marginHorizontal: spacing.md,
    marginBottom: spacing.md,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    backgroundColor: colors.pendingBackground,
    borderRadius: 6,
  },
  noticeText: {
    fontSize: 12,
    color: colors.pendingText,
  },
  loading: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  listContent: {
    paddingBottom: spacing.xl,
  },
  separator: {
    height: 1,
    backgroundColor: colors.border,
    marginHorizontal: spacing.md,
  },
  empty: {
    padding: spacing.xl,
    textAlign: 'center',
    fontSize: 14,
    color: colors.textMuted,
  },
});
