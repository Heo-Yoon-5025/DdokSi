/**
 * 법안 도메인 타입.
 *
 * 백엔드 API 응답 스펙과 1:1로 맞춘다. 필드가 바뀌면 여기부터 고치고,
 * 컴파일 에러가 나는 지점을 따라가면 된다.
 */

/**
 * 법안 처리 상태.
 *
 * 백엔드가 국회 API 의 처리결과 문자열을 이 4가지로 정규화한다.
 * MERGED(대안반영)는 법안 자체는 폐기됐지만 내용이 위원회 대안에 흡수되어 법이 된 경우로,
 * 실측 전체의 약 21%를 차지한다. PASSED 도 DISCARDED 도 아니므로 별도 상태다.
 */
export type BillStatus = 'PENDING' | 'PASSED' | 'MERGED' | 'DISCARDED' | 'UNKNOWN';

/** 목록용 법안 요약 */
export interface BillSummary {
  id: number;
  billNo: string | null;
  title: string;
  status: BillStatus;
  /** 화면 표시용 한글 라벨. 서버가 내려준다 (앱과 레터의 문구가 갈라지지 않도록) */
  statusLabel: string;
  committeeName: string | null;
  proposerSummary: string | null;
  /** ISO 8601 날짜 (YYYY-MM-DD) */
  proposedDate: string | null;
  /** AI 요약. 분석 파이프라인이 아직 없어 현재는 항상 null */
  summary: string | null;
}

/** 상태 변경 이력 한 건 */
export interface BillStatusChange {
  /** null 이면 최초 수집 시점의 기록 */
  fromStatusLabel: string | null;
  toStatusLabel: string;
  fromProcResultRaw: string | null;
  toProcResultRaw: string | null;
  changedAt: string;
}

/** 상세용 법안 정보 */
export interface BillDetail extends Omit<BillSummary, 'summary'> {
  assemblyAge: number | null;
  /** 국회가 내려준 처리결과 원문 (예: 대안반영폐기) */
  procResultRaw: string | null;
  rstProposer: string | null;
  procDate: string | null;
  /** 국회 공식 페이지. AI 요약이 틀렸을 때 원문을 확인할 경로 */
  detailUrl: string | null;
  statusHistory: BillStatusChange[];
}

/** 서버가 정한 페이지 응답 형태 */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  /** 무한 스크롤 종료 판정에 쓴다 */
  last: boolean;
}
