import { StatusBar } from 'expo-status-bar';
import { StyleSheet, View } from 'react-native';
import HomeScreen from './src/screens/HomeScreen';
import { colors } from './src/theme';

/** 앱 진입점. 현재는 메인 화면 하나뿐이라 네비게이션 없이 바로 렌더링한다. */
export default function App() {
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
