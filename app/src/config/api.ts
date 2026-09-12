import { Platform } from 'react-native';

/**
 * 백엔드 API 주소.
 *
 * 하드코딩하지 않는 이유: 로컬(맥북) → 홈서버 → AWS 로 주소가 최소 세 번 바뀐다.
 *
 * 실기기(Expo Go)에서는 localhost 가 폰 자신을 가리키므로 백엔드에 닿지 못한다.
 * 그때는 EXPO_PUBLIC_API_BASE_URL 에 맥북의 LAN IP 를 넣어야 한다
 * (예: EXPO_PUBLIC_API_BASE_URL=http://192.168.0.10:8080).
 */
const DEFAULT_BASE_URL = 'http://localhost:8080';

export const API_BASE_URL =
  process.env.EXPO_PUBLIC_API_BASE_URL ?? DEFAULT_BASE_URL;

/** 실기기에서 localhost 를 쓰고 있으면 개발자가 알아챌 수 있게 알린다. */
export function warnIfUnreachable(): void {
  const isDevice = Platform.OS !== 'web';
  if (isDevice && API_BASE_URL.includes('localhost')) {
    console.warn(
      '[ddoksi] 실기기에서는 localhost 로 백엔드에 닿을 수 없습니다. ' +
        'EXPO_PUBLIC_API_BASE_URL 에 맥북의 LAN IP 를 설정하세요.',
    );
  }
}
