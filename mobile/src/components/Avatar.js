import React, { useState } from 'react';
import { Image, View } from 'react-native';
import { useTheme } from '../theme';

// 웹 public/profile-avatars의 기본 프로필 그림. 서버에는 웹 경로(/profile-avatars/...)로 저장돼 있다.
const AVATARS = {
  '/profile-avatars/mint-hood-384.png': require('../../assets/profile-avatars/mint-hood-384.png'),
  '/profile-avatars/coral-scarf-384.png': require('../../assets/profile-avatars/coral-scarf-384.png'),
  '/profile-avatars/cobalt-visor-384.png': require('../../assets/profile-avatars/cobalt-visor-384.png'),
  '/profile-avatars/golden-headband-384.png': require('../../assets/profile-avatars/golden-headband-384.png'),
  '/profile-avatars/rose-earmuffs-384.png': require('../../assets/profile-avatars/rose-earmuffs-384.png'),
  '/profile-avatars/teal-leaf-384.png': require('../../assets/profile-avatars/teal-leaf-384.png'),
  '/profile-avatars/violet-hood-384.png': require('../../assets/profile-avatars/violet-hood-384.png'),
};
// 웹 DEFAULT_PROFILE_AVATAR와 같은 그림.
const DEFAULT_AVATAR = AVATARS['/profile-avatars/mint-hood-384.png'];

// 프로필 설정에서 고르는 순서와 이름. 웹 src/lib/profileAvatars.ts의 PROFILE_AVATARS와 같다.
export const PROFILE_AVATARS = [
  { src: '/profile-avatars/mint-hood-384.png', name: '민트 후드' },
  { src: '/profile-avatars/coral-scarf-384.png', name: '코랄 스카프' },
  { src: '/profile-avatars/cobalt-visor-384.png', name: '코발트 바이저' },
  { src: '/profile-avatars/golden-headband-384.png', name: '골든 밴드' },
  { src: '/profile-avatars/violet-hood-384.png', name: '바이올렛 후드' },
  { src: '/profile-avatars/teal-leaf-384.png', name: '틸 리프' },
  { src: '/profile-avatars/rose-earmuffs-384.png', name: '로즈 이어머프' },
];
export const isProfileAvatar = url => PROFILE_AVATARS.some(avatar => avatar.src === url);

/** 프로필 사진. 기본 그림은 앱에 든 파일을, 외부 사진(Google 계정 등)은 주소로 불러오고 실패하면 기본 그림으로 바꾼다. */
export function Avatar({ url, size = 36 }) {
  const theme = useTheme();
  const [failed, setFailed] = useState(false);
  const bundled = url ? AVATARS[String(url).replace(/^https?:\/\/[^/]+/, '')] : null;
  const source = bundled ?? (url && /^https?:\/\//.test(url) && !failed ? { uri: url } : DEFAULT_AVATAR);
  return (
    <View style={{ width: size, height: size, borderRadius: size / 2, overflow: 'hidden', backgroundColor: theme.line }}>
      <Image source={source} style={{ width: size, height: size }} onError={() => setFailed(true)} accessibilityIgnoresInvertColors />
    </View>
  );
}
