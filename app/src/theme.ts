/**
 * 디자인 토큰.
 *
 * 컨셉: 장식을 빼고 여백과 타이포그래피로만 정리한다. (그림자/그라데이션 없음)
 * 색은 상태 배지와 강조 요소에만 쓰고, 채도를 낮춰 목록 전체가 시끄러워지지 않게 한다.
 */

export const colors = {
  background: '#ffffff',
  /** 본문 텍스트 */
  text: '#202124',
  /** 보조 정보 (위원회, 날짜 등) */
  textMuted: '#5f6368',
  /** 구분선 */
  border: '#e8eaed',
  /** 선택된 필터 등 강조 요소 */
  accent: '#1a73e8',
  accentSoft: '#e8f0fe',
  /** 오류 안내 */
  danger: '#c5221f',
  dangerSoft: '#fce8e6',
} as const;

/**
 * 상태별 배지 색.
 *
 * MERGED(대안반영)에 파란 계열을 준 이유: 법안 자체는 폐기됐지만 내용이 법이 되었으므로
 * 폐기(회색)와 같게 보이면 안 되고, 통과(초록)와 혼동되어도 안 된다.
 */
export const statusColors = {
  PENDING: { text: '#8a5300', background: '#fef7e0' },
  PASSED: { text: '#137333', background: '#e6f4ea' },
  MERGED: { text: '#1967d2', background: '#e8f0fe' },
  DISCARDED: { text: '#5f6368', background: '#f1f3f4' },
  UNKNOWN: { text: '#5f6368', background: '#f1f3f4' },
} as const;

export const spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
} as const;

/** 웹에서 목록이 화면 끝까지 늘어나면 읽기 힘들어 최대 폭을 제한한다. */
export const CONTENT_MAX_WIDTH = 640;
