import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Linking,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { ApiError, fetchBillDetail } from '../api/bills';
import { BillDetail } from '../types/bill';
import StatusBadge from '../components/StatusBadge';
import { CONTENT_MAX_WIDTH, colors, spacing } from '../theme';

type Props = {
  /** 경로에서 읽은 법안 id. 주소를 손으로 고쳐 넣은 경우 null 이 올 수 있다 */
  billId: number | null;
};

/** 제안일(ISO 날짜)을 'YYYY.MM.DD' 형태로 바꾼다. */
function formatDate(isoDate: string | null): string | null {
  return isoDate ? isoDate.replace(/-/g, '.') : null;
}

/**
 * 법안 상세.
 *
 * <p><b>화면 구성의 핵심 판단: 제안이유 원문은 접어 둔다.</b> 원문은 중앙값 499자, 최대
 * 1만자가 넘는 법률 문체라 먼저 펼쳐 두면 읽을 사람이 없다. AI 요약을 기본으로 보여주고
 * 원문은 "확인하고 싶은 사람" 을 위해 남긴다 — 지우지는 않는다. 요약이 틀렸을 때
 * 기댈 곳이 필요하고, 그것이 없으면 이 서비스는 검증 불가능한 말만 하는 셈이 된다.
 *
 * <p>분석과 원문 모두 없을 수 있다. 원문이 없는 법안이 41건 있고, 분석은 아직
 * 생성되지 않았을 수 있다. <b>셋 중 무엇이 없어도 화면은 그려져야 한다.</b>
 */
export default function BillDetailScreen({ billId }: Props) {
  const [bill, setBill] = useState<BillDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [textExpanded, setTextExpanded] = useState(false);

  const load = useCallback(async () => {
    if (billId === null) {
      setError('잘못된 주소입니다.');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      setBill(await fetchBillDetail(billId));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '알 수 없는 오류가 발생했습니다.');
      setBill(null);
    } finally {
      setLoading(false);
    }
  }, [billId]);

  useEffect(() => {
    load();
  }, [load]);

  if (loading) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color={colors.textMuted} />
      </View>
    );
  }

  if (error || !bill) {
    return (
      <View style={styles.centered}>
        <View style={styles.errorBox}>
          <Text style={styles.errorText}>{error ?? '법안을 찾을 수 없습니다.'}</Text>
        </View>
        <Pressable style={styles.retry} onPress={load}>
          <Text style={styles.retryText}>다시 시도</Text>
        </Pressable>
      </View>
    );
  }

  const analysis = bill.analysis;
  const meta = [bill.committeeName, bill.proposerSummary, formatDate(bill.proposedDate)]
    .filter(Boolean)
    .join(' · ');

  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.scrollContent}>
      <View style={styles.content}>
        {/* ── 머리말 ─────────────────────────────── */}
        <View style={styles.section}>
          <StatusBadge status={bill.status} label={bill.statusLabel} />
          <Text style={styles.title}>{bill.title}</Text>
          {meta ? <Text style={styles.meta}>{meta}</Text> : null}
        </View>

        {/* 상태 설명문. 라벨만으로는 '통합 처리'(대안반영폐기)를 설명할 수 없어
            서버가 문장을 함께 내려준다. */}
        {bill.statusDescription ? (
          <View style={styles.statusNote}>
            <Text style={styles.statusNoteText}>{bill.statusDescription}</Text>
          </View>
        ) : null}

        {/* ── AI 분석 ────────────────────────────── */}
        {analysis ? (
          <View style={styles.section}>
            {analysis.hook ? <Text style={styles.hook}>{analysis.hook}</Text> : null}

            {analysis.topics.length > 0 ? (
              <View style={styles.topics}>
                {analysis.topics.map((topic) => (
                  <View key={topic} style={styles.topic}>
                    <Text style={styles.topicText}>{topic}</Text>
                  </View>
                ))}
              </View>
            ) : null}

            {/* 근거가 없으면 서버가 비워 보낸다. 빈 항목은 건너뛴다 —
                제목만 남은 빈 칸을 보여주면 생성이 실패한 것처럼 보인다. */}
            <Field label="무슨 내용인가" value={analysis.summary} />
            <Field label="누구에게 닿는가" value={analysis.example} />
            <Field label="왜 나왔나" value={analysis.background} />

            <Text style={styles.disclaimer}>
              AI 가 제안이유 원문을 바탕으로 정리한 내용입니다. 정확한 내용은 원문을 확인해 주세요.
            </Text>
          </View>
        ) : (
          <View style={styles.section}>
            <Text style={styles.emptyAnalysis}>아직 요약이 준비되지 않은 법안입니다.</Text>
          </View>
        )}

        {/* ── 제안이유 원문 (접힘) ────────────────── */}
        {bill.billText ? (
          <View style={styles.section}>
            <Pressable
              style={styles.toggle}
              onPress={() => setTextExpanded((prev) => !prev)}
              accessibilityRole="button"
              accessibilityState={{ expanded: textExpanded }}
            >
              <Text style={styles.toggleText}>
                {textExpanded ? '제안이유 원문 접기' : '제안이유 원문 보기'}
              </Text>
              <Text style={styles.toggleIcon}>{textExpanded ? '−' : '+'}</Text>
            </Pressable>

            {textExpanded ? <Text style={styles.billText}>{bill.billText}</Text> : null}
          </View>
        ) : null}

        {/* ── 처리 경과 ───────────────────────────── */}
        {bill.statusHistory.length > 0 ? (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>처리 경과</Text>
            {bill.statusHistory.map((change, index) => (
              <View key={`${change.changedAt}-${index}`} style={styles.historyRow}>
                <Text style={styles.historyDate}>
                  {formatDate(change.changedAt.slice(0, 10))}
                </Text>
                <Text style={styles.historyText}>
                  {change.fromStatusLabel
                    ? `${change.fromStatusLabel} → ${change.toStatusLabel}`
                    : change.toStatusLabel}
                  {change.toProcResultRaw ? ` (${change.toProcResultRaw})` : ''}
                </Text>
              </View>
            ))}
          </View>
        ) : null}

        {/* 국회 공식 페이지. 요약이 틀렸을 때 확인할 경로이므로 항상 남긴다. */}
        {bill.detailUrl ? (
          <Pressable
            style={styles.officialLink}
            onPress={() => Linking.openURL(bill.detailUrl!)}
            accessibilityRole="link"
          >
            <Text style={styles.officialLinkText}>국회 의안정보시스템에서 보기</Text>
          </Pressable>
        ) : null}
      </View>
    </ScrollView>
  );
}

/** 분석 항목 한 칸. 값이 비어 있으면 아무것도 그리지 않는다. */
function Field({ label, value }: { label: string; value: string | null }) {
  if (!value) {
    return null;
  }
  return (
    <View style={styles.field}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <Text style={styles.fieldValue}>{value}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: colors.background,
  },
  scrollContent: {
    alignItems: 'center',
    paddingBottom: spacing.xl,
  },
  content: {
    width: '100%',
    maxWidth: CONTENT_MAX_WIDTH,
    paddingHorizontal: spacing.md,
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.lg,
    gap: spacing.md,
    backgroundColor: colors.background,
  },

  section: {
    paddingVertical: spacing.lg,
    gap: spacing.sm,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '600',
    color: colors.textMuted,
    marginBottom: spacing.xs,
  },

  title: {
    fontSize: 20,
    fontWeight: '700',
    color: colors.text,
    lineHeight: 30,
  },
  meta: {
    fontSize: 12,
    color: colors.textMuted,
  },

  statusNote: {
    marginTop: spacing.md,
    padding: spacing.md,
    borderRadius: 8,
    backgroundColor: colors.accentSoft,
  },
  statusNoteText: {
    fontSize: 13,
    color: colors.text,
    lineHeight: 20,
  },

  hook: {
    fontSize: 18,
    fontWeight: '700',
    color: colors.text,
    lineHeight: 28,
    marginBottom: spacing.xs,
  },
  topics: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    marginBottom: spacing.sm,
  },
  topic: {
    paddingHorizontal: spacing.sm,
    paddingVertical: 3,
    borderRadius: 4,
    backgroundColor: colors.accentSoft,
  },
  topicText: {
    fontSize: 11,
    fontWeight: '600',
    color: colors.accent,
  },

  field: {
    gap: spacing.xs,
    marginTop: spacing.md,
  },
  fieldLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: colors.textMuted,
  },
  fieldValue: {
    fontSize: 15,
    color: colors.text,
    lineHeight: 24,
  },
  disclaimer: {
    fontSize: 11,
    color: colors.textMuted,
    lineHeight: 18,
    marginTop: spacing.md,
  },
  emptyAnalysis: {
    fontSize: 14,
    color: colors.textMuted,
  },

  toggle: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: spacing.xs,
  },
  toggleText: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.accent,
  },
  toggleIcon: {
    fontSize: 18,
    color: colors.accent,
  },
  billText: {
    fontSize: 14,
    color: colors.text,
    lineHeight: 24,
    marginTop: spacing.sm,
  },

  historyRow: {
    flexDirection: 'row',
    gap: spacing.md,
    paddingVertical: spacing.xs,
  },
  historyDate: {
    fontSize: 12,
    color: colors.textMuted,
    width: 84,
  },
  historyText: {
    flex: 1,
    fontSize: 13,
    color: colors.text,
    lineHeight: 20,
  },

  officialLink: {
    marginTop: spacing.lg,
    paddingVertical: spacing.md,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: 8,
  },
  officialLinkText: {
    fontSize: 14,
    fontWeight: '600',
    color: colors.accent,
  },

  errorBox: {
    padding: spacing.md,
    borderRadius: 8,
    backgroundColor: colors.dangerSoft,
  },
  errorText: {
    fontSize: 14,
    color: colors.danger,
    textAlign: 'center',
  },
  retry: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: colors.border,
  },
  retryText: {
    fontSize: 14,
    color: colors.text,
  },
});
