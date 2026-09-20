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
  /** AI 요약. 아직 분석되지 않은 법안은 null */
  summary: string | null;
}

/**
 * AI 분석 결과.
 *
 * 항목마다 서로를 참조하지 않고 독립적으로 읽히게 생성된다. 상세 화면에서는 세로로
 * 이어 보여주지만, 레터와 카드뉴스는 항목 단위로 잘라 쓸 수 있다.
 *
 * 근거가 없으면 서버가 비워 보내므로 항목별로 null 을 허용한다. "없으면 지어낸다" 를
 * 막기 위한 설계라, 화면도 빈 항목을 자연스럽게 건너뛰어야 한다.
 *
 * pros/cons 는 내려오지 않는다. 입력인 제안이유가 발의자의 설득 문서라 반대 근거가
 * 본문에 없고, 요구하면 모델이 만들어낸다.
 */
export interface BillAnalysis {
  /** SNS 첫 줄로 그대로 쓸 한 문장. 법안명이 아니라 내용 핵심어를 담는다 */
  hook: string | null;
  /** 제도를 무엇에서 무엇으로 바꾸는지 */
  summary: string | null;
  /** 누구의 어떤 상황과 닿아 있는지 */
  example: string | null;
  /** 왜 지금 이 법안이 나왔는지 */
  background: string | null;
  /** 고정 어휘에서 고른 주제 태그 0~2개 */
  topics: string[];
  /** 품질 문제를 추적할 때 필요하다 */
  generatedModel: string | null;
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
  /** 상태를 풀어 쓴 한 문장. 서버가 내려준다 — 앱과 레터가 각자 문장을 만들면 갈라진다 */
  statusDescription: string | null;
  /** 제안이유 및 주요내용 원문. 국회가 본문을 올리지 않은 41건은 null */
  billText: string | null;
  /** 아직 생성 전이거나 실패한 법안은 null. 화면은 이것이 없어도 그려져야 한다 */
  analysis: BillAnalysis | null;
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
