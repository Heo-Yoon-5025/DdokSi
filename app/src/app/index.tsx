import HomeScreen from '../screens/HomeScreen';

/**
 * 목록 화면. 경로는 {@code /} 다.
 *
 * <p>화면 구현은 screens/ 에 그대로 둔다. 라우트 파일은 "어느 경로에 무엇이 오는가" 만
 * 정하고, 화면 자체는 라우팅을 몰라도 되게 나눠 둔 것이다.
 */
export default function IndexRoute() {
  return <HomeScreen />;
}
