import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { ApiError, fetchBills } from '../api/bills';
import { BillSummary } from '../types/bill';
import BillCard from '../components/BillCard';
import FilterTabs, { FilterValue } from '../components/FilterTabs';
import { CONTENT_MAX_WIDTH, colors, spacing } from '../theme';

const PAGE_SIZE = 20;
/** 검색어를 한 글자마다 보내지 않도록 입력이 멈춘 뒤 호출한다. */
const SEARCH_DEBOUNCE_MS = 400;

/**
 * 메인 화면. (로그인 없음)
 *
 * 법안 목록을 조회해 보여주고 상태 필터와 키워드 검색을 제공한다.
 * 전체 2만 건 규모라 한 번에 받지 않고 스크롤에 따라 이어서 받는다.
 */
export default function HomeScreen() {
  const [bills, setBills] = useState<BillSummary[]>([]);
  const [filter, setFilter] = useState<FilterValue>(undefined);
  const [searchInput, setSearchInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [totalElements, setTotalElements] = useState(0);

  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pageRef = useRef(0);
  const lastRef = useRef(false);

  // 입력이 멈춘 뒤에만 검색어를 확정한다. 타이핑 중 매 글자마다 요청하지 않기 위한 것이다.
  useEffect(() => {
    const timer = setTimeout(() => setKeyword(searchInput.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [searchInput]);

  /** 첫 페이지를 불러온다. 필터나 검색어가 바뀌면 목록을 처음부터 다시 구성한다. */
  const loadFirstPage = useCallback(async (status: FilterValue, searchWord: string) => {
    setLoading(true);
    setError(null);
    try {
      const page = await fetchBills({ status, keyword: searchWord, page: 0, size: PAGE_SIZE });
      setBills(page.content);
      setTotalElements(page.totalElements);
      pageRef.current = 0;
      lastRef.current = page.last;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '알 수 없는 오류가 발생했습니다.');
      setBills([]);
      setTotalElements(0);
    } finally {
      // 실패해도 로딩 표시는 반드시 해제해 화면이 멈춘 것처럼 보이지 않게 한다
      setLoading(false);
    }
  }, []);

  /** 다음 페이지를 이어서 불러온다. */
  const loadMore = useCallback(async () => {
    // 이미 요청 중이거나 마지막 페이지면 아무것도 하지 않는다.
    // FlatList 의 onEndReached 는 한 번의 스크롤에서 여러 번 불릴 수 있다.
    if (loading || loadingMore || lastRef.current) {
      return;
    }
    setLoadingMore(true);
    try {
      const next = pageRef.current + 1;
      const page = await fetchBills({
        status: filter,
        keyword,
        page: next,
        size: PAGE_SIZE,
      });
      setBills((prev) => [...prev, ...page.content]);
      pageRef.current = next;
      lastRef.current = page.last;
    } catch {
      // 추가 로딩 실패는 첫 페이지 실패와 다르게 다룬다.
      // 이미 보고 있는 목록을 오류 화면으로 치우지 않고, 다음 스크롤에서 다시 시도되게 둔다.
    } finally {
      setLoadingMore(false);
    }
  }, [filter, keyword, loading, loadingMore]);

  useEffect(() => {
    loadFirstPage(filter, keyword);
  }, [filter, keyword, loadFirstPage]);

  return (
    <View style={styles.screen}>
      <View style={styles.content}>
        <View style={styles.header}>
          <Text style={styles.logo}>똑시</Text>
          <Text style={styles.tagline}>국회에서 지금 무슨 일이 있었는지</Text>
        </View>

        <TextInput
          style={styles.search}
          value={searchInput}
          onChangeText={setSearchInput}
          placeholder="법안명 검색"
          placeholderTextColor={colors.textMuted}
          returnKeyType="search"
          autoCorrect={false}
        />

        <FilterTabs value={filter} onChange={setFilter} />

        {/* 조회 결과 건수. 필터가 실제로 걸렸는지 사용자가 확인할 수 있게 한다 */}
        {!loading && !error ? (
          <Text style={styles.count}>{totalElements.toLocaleString()}건</Text>
        ) : null}

        {loading ? (
          <View style={styles.centered}>
            <ActivityIndicator color={colors.textMuted} />
          </View>
        ) : error ? (
          <View style={styles.centered}>
            <View style={styles.errorBox}>
              <Text style={styles.errorText}>{error}</Text>
            </View>
            <Pressable style={styles.retry} onPress={() => loadFirstPage(filter, keyword)}>
              <Text style={styles.retryText}>다시 시도</Text>
            </Pressable>
          </View>
        ) : (
          <FlatList
            data={bills}
            keyExtractor={(item) => String(item.id)}
            renderItem={({ item }) => <BillCard bill={item} />}
            ItemSeparatorComponent={() => <View style={styles.separator} />}
            ListEmptyComponent={<Text style={styles.empty}>해당하는 법안이 없습니다.</Text>}
            contentContainerStyle={styles.listContent}
            onEndReached={loadMore}
            onEndReachedThreshold={0.5}
            ListFooterComponent={
              loadingMore ? (
                <View style={styles.footer}>
                  <ActivityIndicator color={colors.textMuted} />
                </View>
              ) : null
            }
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
    paddingBottom: spacing.md,
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
  search: {
    marginHorizontal: spacing.md,
    marginBottom: spacing.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm + 2,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 999,
    fontSize: 14,
    color: colors.text,
  },
  count: {
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.sm,
    fontSize: 12,
    color: colors.textMuted,
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.md,
    paddingHorizontal: spacing.md,
  },
  errorBox: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    backgroundColor: colors.dangerSoft,
    borderRadius: 6,
  },
  errorText: {
    fontSize: 13,
    color: colors.danger,
    textAlign: 'center',
  },
  retry: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 999,
  },
  retryText: {
    fontSize: 13,
    color: colors.accent,
    fontWeight: '600',
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
  footer: {
    paddingVertical: spacing.lg,
  },
});
