import { useEffect } from 'react';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet, View } from 'react-native';
import HomeScreen from './src/screens/HomeScreen';
import { warnIfUnreachable } from './src/config/api';
import { colors } from './src/theme';

/** 앱 진입점. 현재는 메인 화면 하나뿐이라 네비게이션 없이 바로 렌더링한다. */
export default function App() {
  // 실기기에서 localhost 로 백엔드를 가리키고 있으면 개발자가 알아챌 수 있게 알린다
  useEffect(() => {
    warnIfUnreachable();
  }, []);

  return (
    <View style={styles.root}>
      <StatusBar style="dark" />
      <HomeScreen />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.background,
  },
});
