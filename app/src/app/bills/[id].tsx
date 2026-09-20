import { useLocalSearchParams } from 'expo-router';
import BillDetailScreen from '../../screens/BillDetailScreen';

/**
 * 법안 상세. 경로는 {@code /bills/1107} 이다.
 *
 * <p>이 경로가 파일 이름에서 그대로 나오기 때문에 레터와 SNS 가 링크를 걸 수 있다.
 * 앱이 설치돼 있으면 {@code ddoksi://bills/1107} 로 앱이 열리고, 없으면 같은 경로의
 * 웹 페이지가 열린다.
 *
 * <p>id 를 여기서 숫자로 바꿔 넘긴다. URL 파라미터는 언제나 문자열이고, 사람이 주소를
 * 직접 고쳐 넣을 수도 있다 — 화면이 그 검증까지 떠안을 이유는 없다.
 */
export default function BillDetailRoute() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const billId = Number(id);

  return <BillDetailScreen billId={Number.isFinite(billId) ? billId : null} />;
}
