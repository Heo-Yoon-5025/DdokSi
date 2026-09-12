/**
 * 법안 데이터 조회 계층.
 *
 * 화면 컴포넌트는 이 모듈의 함수만 호출하고 데이터 출처나 HTTP 세부사항을 알지 못한다.
 * 목업에서 실제 API 로 바꾼 작업이 이 파일 안에서만 끝난 것도 그 분리 덕분이다.
 */
import { API_BASE_URL } from '../config/api';
import { BillDetail, BillStatus, BillSummary, PageResponse } from '../types/bill';

/** 요청이 응답하지 않을 때 화면이 영원히 로딩 상태로 남지 않게 한다. */
const TIMEOUT_MS = 10_000;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * 공통 요청 처리.
 *
 * 서버는 오류를 RFC 9457 ProblemDetail 로 내려준다({ title, detail, status }).
 * 그 title 을 사용자에게 보여줄 메시지로 쓴다.
 */
async function request<T>(path: string, params?: Record<string, string | number | undefined>): Promise<T> {
  const url = new URL(`${API_BASE_URL}${path}`);
  if (params) {
    // 값이 없는 파라미터는 보내지 않는다. 서버에서 "필터 없음"으로 처리된다.
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== '') {
        url.searchParams.set(key, String(value));
      }
    });
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);

  try {
    const response = await fetch(url.toString(), { signal: controller.signal });

    if (!response.ok) {
      // 오류 본문 파싱에 실패해도 상태 코드만으로 메시지를 만든다
      let message = `요청이 실패했습니다 (${response.status})`;
      try {
        const problem = await response.json();
        if (problem?.title) {
          message = problem.title;
        }
      } catch {
        // 본문이 JSON 이 아닌 경우는 무시하고 기본 메시지를 쓴다
      }
      throw new ApiError(message, response.status);
    }

    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    // 타임아웃(abort)과 네트워크 단절을 구분해 안내한다.
    // 실기기에서 localhost 를 쓰면 여기로 온다.
    if (error instanceof Error && error.name === 'AbortError') {
      throw new ApiError('서버 응답이 너무 늦습니다. 잠시 후 다시 시도해 주세요.');
    }
    throw new ApiError('서버에 연결할 수 없습니다. 네트워크와 API 주소를 확인해 주세요.');
  } finally {
    clearTimeout(timeout);
  }
}

export interface BillSearchParams {
  status?: BillStatus;
  committee?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

/** 법안 목록을 조회한다. 조건을 생략하면 전체를 최신 제안일 순으로 돌려준다. */
export function fetchBills(params: BillSearchParams = {}): Promise<PageResponse<BillSummary>> {
  return request<PageResponse<BillSummary>>('/api/bills', {
    status: params.status,
    committee: params.committee,
    keyword: params.keyword,
    page: params.page ?? 0,
    size: params.size ?? 20,
  });
}

/** 법안 상세를 조회한다. */
export function fetchBillDetail(id: number): Promise<BillDetail> {
  return request<BillDetail>(`/api/bills/${id}`);
}

/** 필터 UI 에 채울 상임위 목록을 조회한다. */
export function fetchCommittees(): Promise<string[]> {
  return request<string[]>('/api/bills/committees');
}
