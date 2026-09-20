import { useEffect } from 'react';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { warnIfUnreachable } from '../config/api';
import { colors } from '../theme';

/**
 * 모든 화면을 감싸는 루트 레이아웃.
 *
 * <p>expo-router 는 이 파일 위치(src/app)를 라우트 뿌리로 삼는다. 프로젝트에 이미
 * src 디렉터리가 있어 여기에 둔다 — 그러지 않으면 Expo 프로젝트 폴더가 app 이라
 * app/app 이라는 헷갈리는 경로가 된다.
 *
 * <p>헤더는 Stack 이 그린다. 뒤로가기 버튼, 제스처, 안드로이드 하드웨어 백키가
 * 여기서 한 번에 따라온다 — 직접 구현하면 플랫폼마다 다르게 틀린다.
 */
export default function RootLayout() {
  // 실기기에서 localhost 로 백엔드를 가리키고 있으면 개발자가 알아챌 수 있게 알린다
  useEffect(() => {
    warnIfUnreachable();
  }, []);

  return (
    <>
      <StatusBar style="dark" />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: colors.background },
          headerTintColor: colors.text,
          headerTitleStyle: { fontSize: 16, fontWeight: '600' },
          headerShadowVisible: false,
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        {/* 목록은 자체 헤더(로고)를 그리므로 네비게이션 헤더를 숨긴다 */}
        <Stack.Screen name="index" options={{ headerShown: false }} />
        <Stack.Screen name="bills/[id]" options={{ title: '법안 상세' }} />
      </Stack>
    </>
  );
}
