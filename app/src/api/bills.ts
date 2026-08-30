/**
 * 법안 데이터 조회 계층.
 *
 * ⚠️ 현재는 전부 목업이다. 백엔드 API가 없고 국회 Open API 인증키도 아직 없다.
 *
 * 화면 컴포넌트는 이 모듈의 함수만 호출하고 데이터 출처를 알지 못한다.
 * 따라서 실제 API가 준비되면 아래 fetchBills 내부만 fetch 호출로 바꾸면 되고,
 * 화면 코드는 손대지 않는다.
 */
import { Bill, BillStatus } from '../types/bill';

/** 목업 데이터. 실제 발의 법안이 아니라 화면 확인용으로 지어낸 예시다. */
const MOCK_BILLS: Bill[] = [
  {
    id: '1',
    billNo: '2200101',
    title: '주택임대차보호법 일부개정법률안',
    status: 'PENDING',
    committee: '법제사법위원회',
    proposer: '홍길동 의원 등 12인',
    proposedDate: '2026-08-21',
    summary: '임대차 계약 갱신 시 임대인이 갱신 거절 사유를 서면으로 통지하도록 의무화한다.',
  },
  {
    id: '2',
    billNo: '2200098',
    title: '개인정보 보호법 일부개정법률안',
    status: 'PASSED',
    committee: '정무위원회',
    proposer: '김철수 의원 등 21인',
    proposedDate: '2026-07-14',
    summary: '개인정보 유출 사고 발생 시 정보주체에 대한 통지 기한을 72시간 이내로 단축한다.',
  },
  {
    id: '3',
    billNo: '2200095',
    title: '중소기업기본법 일부개정법률안',
    status: 'PENDING',
    committee: '산업통상자원중소벤처기업위원회',
    proposer: '이영희 의원 등 10인',
    proposedDate: '2026-08-11',
    summary: '중소기업 판정 기준에서 일시적 매출 증가를 유예하는 규정을 신설한다.',
  },
  {
    id: '4',
    billNo: '2200087',
    title: '도로교통법 일부개정법률안',
    status: 'PASSED',
    committee: '행정안전위원회',
    proposer: '박민수 의원 등 15인',
    proposedDate: '2026-06-30',
    summary: '어린이보호구역 내 불법 주정차 과태료를 상향하고 단속 시간을 확대한다.',
  },
  {
    id: '5',
    billNo: '2200083',
    title: '근로기준법 일부개정법률안',
    status: 'PENDING',
    committee: '환경노동위원회',
    proposer: '최지은 의원 등 18인',
    proposedDate: '2026-08-05',
    summary: '상시 5인 미만 사업장에도 연차 유급휴가 규정을 단계적으로 적용한다.',
  },
  {
    id: '6',
    billNo: '2200079',
    title: '전기통신사업법 일부개정법률안',
    status: 'PENDING',
    committee: '과학기술정보방송통신위원회',
    proposer: '정다은 의원 등 11인',
    proposedDate: '2026-07-28',
    summary: '통신사가 요금제 변경 시 이용자에게 사전 고지할 의무를 명확히 한다.',
  },
  {
    id: '7',
    billNo: '2200071',
    title: '국민건강보험법 일부개정법률안',
    status: 'PASSED',
    committee: '보건복지위원회',
    proposer: '강현우 의원 등 24인',
    proposedDate: '2026-06-12',
    summary: '장기 요양 급여의 본인부담 상한액 산정 방식을 소득 구간별로 세분화한다.',
  },
  {
    id: '8',
    billNo: '2200064',
    title: '학교급식법 일부개정법률안',
    status: 'PENDING',
    committee: '교육위원회',
    proposer: '윤서연 의원 등 9인',
    proposedDate: '2026-07-03',
    summary: '학교급식 식재료의 원산지 정보를 학부모에게 상시 공개하도록 한다.',
  },
];

/** 목업이 즉시 응답하면 로딩 상태 처리가 제대로 동작하는지 확인할 수 없어 지연을 준다. */
const MOCK_DELAY_MS = 400;

/**
 * 법안 목록을 조회한다.
 *
 * @param status 지정하면 해당 상태만 반환하고, 생략하면 전체를 반환한다.
 */
export async function fetchBills(status?: BillStatus): Promise<Bill[]> {
  await new Promise((resolve) => setTimeout(resolve, MOCK_DELAY_MS));

  // 상태 필터가 없으면 전체 반환
  if (!status) {
    return MOCK_BILLS;
  }
  return MOCK_BILLS.filter((bill) => bill.status === status);
}
