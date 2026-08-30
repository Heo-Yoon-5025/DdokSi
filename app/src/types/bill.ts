/**
 * 법안 도메인 타입.
 *
 * 지금은 목업 데이터의 형태를 정의하지만, 이후 백엔드 API 응답 스펙이 확정되면
 * 이 파일이 프론트-백엔드 간 계약(contract) 역할을 한다.
 * 필드가 바뀌면 여기부터 고치고, 컴파일 에러가 나는 지점을 따라가면 된다.
 */

/** 법안 처리 상태. 국회 API의 실제 상태 문자열 확인 후 매핑 규칙을 확정해야 한다. */
export type BillStatus = 'PENDING' | 'PASSED';

export interface Bill {
  /** 내부 식별자 */
  id: string;
  /** 의안번호 (국회에서 부여하는 공식 번호) */
  billNo: string;
  /** 법안명 */
  title: string;
  status: BillStatus;
  /** 소관 상임위원회 */
  committee: string;
  /** 대표발의자 표기 */
  proposer: string;
  /** 제안일 (ISO 8601 날짜) */
  proposedDate: string;
  /** AI가 생성할 한 줄 요약. 생성 실패 시 비어 있을 수 있다. */
  summary: string;
}

/** 상태 값 → 화면에 노출할 한글 라벨 */
export const STATUS_LABEL: Record<BillStatus, string> = {
  PENDING: '논의중',
  PASSED: '통과',
};
