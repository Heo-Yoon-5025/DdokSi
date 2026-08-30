/**
 * 디자인 토큰.
 *
 * 색과 여백을 여기 모아둔다. 화면이 늘어나도 톤이 흐트러지지 않고,
 * 나중에 다크 모드를 넣을 때 이 파일만 확장하면 된다.
 *
 * 컨셉: 장식을 빼고 여백과 타이포그래피로만 정리한다. (그림자/그라데이션 없음)
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

  /** 상태 배지 — 채도를 낮춰 목록 전체가 시끄러워지지 않게 한다 */
  pendingText: '#8a5300',
  pendingBackground: '#fef7e0',
  passedText: '#137333',
  passedBackground: '#e6f4ea',
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
