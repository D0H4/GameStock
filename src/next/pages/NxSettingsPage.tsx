import { onAuthStateChanged, type User } from "firebase/auth";
import { useEffect, useState } from "react";
import { apiFetch } from "../../lib/api";
import { openLoginPrompt } from "../../lib/auth";
import { firebaseAuth } from "../../lib/firebase";
import { setProfile as shareProfile, type Profile } from "../../lib/profile";
import { DEFAULT_PROFILE_AVATAR, isProfileAvatar, PROFILE_AVATARS } from "../../lib/profileAvatars";

/* 프로필 조회·저장·계좌 초기화 규칙은 기존 설정 화면(AccountSettings)과 같다. */
export function NxSettingsPage() {
  const [user, setUser] = useState<User | null>(firebaseAuth.currentUser);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [selectedAvatar, setSelectedAvatar] = useState("");
  const [nickname, setNickname] = useState("");
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [message, setMessage] = useState("");
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => onAuthStateChanged(firebaseAuth, setUser), []);

  const nextNicknameChange = profile?.nicknameChangeAvailableAt ? Date.parse(profile.nicknameChangeAvailableAt) : null;
  const nicknameLocked = profile?.nicknameChangeAvailable === false && (nextNicknameChange === null || now < nextNicknameChange);

  useEffect(() => {
    if (!user) {
      setProfile(null);
      setSelectedAvatar("");
      return;
    }
    let active = true;
    setLoading(true);
    setMessage("");
    void apiFetch<Profile>("/api/profile")
      .then((next) => {
        if (!active) return;
        setProfile(next);
        setNickname(next.nickname ?? "");
        setSelectedAvatar(next.profileImageUrl ?? "");
      })
      .catch((error) => { if (active) setMessage(error instanceof Error ? error.message : "계정 정보를 불러오지 못했습니다."); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [user]);

  const applyProfile = (next: Profile) => {
    setProfile(next);
    shareProfile(next);
    setNickname(next.nickname);
    setSelectedAvatar(next.profileImageUrl ?? "");
  };

  const saveNickname = async () => {
    const value = nickname.trim();
    if (value.length < 2 || value.length > 10) {
      setMessage("닉네임은 2~10자로 입력해 주세요.");
      return;
    }
    setSaving(true);
    setMessage("");
    try {
      applyProfile(await apiFetch<Profile>("/api/profile", { method: "PATCH", body: JSON.stringify({ nickname: value }) }));
      setMessage("닉네임을 저장했어요.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "닉네임 저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const saveAvatar = async () => {
    if (!profile || !selectedAvatar || selectedAvatar === (profile.profileImageUrl ?? "")) return;
    setSaving(true);
    setMessage("");
    try {
      applyProfile(await apiFetch<Profile>("/api/profile", { method: "PATCH", body: JSON.stringify({ nickname: profile.nickname, profileImageUrl: selectedAvatar }) }));
      setMessage("프로필 사진을 저장했어요.");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "프로필 사진 저장에 실패했습니다.");
    } finally {
      setSaving(false);
    }
  };

  const resetAccount = async () => {
    if (!window.confirm("현금과 보유 주식, 거래 내역, 투자 미션을 초기 상태로 되돌릴까요?")) return;
    setResetting(true);
    setMessage("");
    try {
      await apiFetch("/api/account/reset", { method: "DELETE" });
      window.location.reload();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "계좌 초기화에 실패했습니다.");
    } finally {
      setResetting(false);
    }
  };

  const chosenAvatar = PROFILE_AVATARS.find((avatar) => avatar.src === selectedAvatar);
  const avatarSaved = selectedAvatar !== "" && selectedAvatar === (profile?.profileImageUrl ?? "");
  const avatarStatus = chosenAvatar
    ? avatarSaved ? `${chosenAvatar.name} 사용 중` : `${chosenAvatar.name} 선택됨. 저장하면 바뀌어요.`
    : selectedAvatar ? "지금은 로그인 계정 사진을 쓰고 있어요. 바꾸려면 캐릭터를 골라 주세요."
    : "캐릭터를 하나 골라 주세요.";
  const preview = selectedAvatar || profile?.profileImageUrl || DEFAULT_PROFILE_AVATAR;

  return (
    <div className="nx-screen nx-settings">
      <header className="nx-screen-head">
        <h1 className="nx-page-title">설정</h1>
      </header>

      {!user ? (
        <section className="nx-panel nx-empty-state">
          <p>설정은 로그인 후 이용할 수 있어요.</p>
          <button type="button" className="nx-btn is-primary" onClick={() => openLoginPrompt("/settings")}>로그인</button>
        </section>
      ) : (
        <div className="nx-settings-layout" aria-busy={loading}>
          <aside className="nx-panel nx-profile-card" aria-label="내 프로필 미리보기">
            <img src={preview} alt="" onError={(event) => { event.currentTarget.src = DEFAULT_PROFILE_AVATAR; }} />
            <p className="nx-profile-name">{profile?.nickname ?? (loading ? "불러오는 중" : "-")}</p>
            <p className="nx-muted">{user.email ?? "로그인 계정"}</p>
          </aside>

          <div className="nx-settings-main">
            {message && <p className="nx-msg" role="status">{message}</p>}

            <section className="nx-panel" aria-labelledby="nx-nick-title">
              <header className="nx-panel-head"><h2 id="nx-nick-title" className="nx-panel-title">닉네임</h2></header>
              {loading ? <div className="nx-skeleton"><span /></div> : (
                <form className="nx-form" onSubmit={(event) => { event.preventDefault(); void saveNickname(); }}>
                  <label htmlFor="nx-nickname" className="vh">닉네임</label>
                  <div className="nx-inline-form">
                    <input id="nx-nickname" className="nx-input" value={nickname} disabled={nicknameLocked} minLength={2} maxLength={10} required onChange={(event) => setNickname(event.target.value)} />
                    <button type="submit" className="nx-btn is-primary" disabled={saving || !profile || nicknameLocked || nickname.trim() === profile.nickname}>{saving ? "저장 중…" : "저장"}</button>
                  </div>
                  <p className="nx-field-hint">
                    {nicknameLocked && nextNicknameChange !== null
                      ? `다음 변경 가능: ${new Date(nextNicknameChange).toLocaleString("ko-KR", { timeZone: "Asia/Seoul" })} (한국시간)`
                      : "닉네임은 3일에 한 번 바꿀 수 있어요 · 2~10자"}
                  </p>
                </form>
              )}
            </section>

            <section className="nx-panel" aria-labelledby="nx-avatar-title">
              <header className="nx-panel-head">
                <h2 id="nx-avatar-title" className="nx-panel-title">프로필 사진</h2>
                <span className="nx-muted">상단 프로필 버튼과 랭킹에 보여요</span>
              </header>
              {loading ? <div className="nx-skeleton"><span /><span /></div> : (
                <fieldset className="nx-avatar-picker">
                  <legend className="vh">프로필 사진 선택</legend>
                  {profile?.profileImageUrl && !isProfileAvatar(profile.profileImageUrl) && (
                    <p className="nx-muted nx-avatar-current">
                      <img src={profile.profileImageUrl} alt="" onError={(event) => { event.currentTarget.src = DEFAULT_PROFILE_AVATAR; }} />
                      현재 계정 사진을 쓰고 있어요
                    </p>
                  )}
                  <div className="nx-avatar-grid" role="group" aria-label="프로필 사진 선택">
                    {PROFILE_AVATARS.map((avatar) => {
                      const selected = selectedAvatar === avatar.src;
                      return (
                        <button key={avatar.id} type="button" className={`nx-avatar-option${selected ? " is-active" : ""}`} aria-pressed={selected}
                          aria-label={`${avatar.name} 프로필 사진 선택`} title={avatar.description} onClick={() => setSelectedAvatar(avatar.src)}>
                          <img src={avatar.src} alt="" />
                          <span>{avatar.name}</span>
                        </button>
                      );
                    })}
                  </div>
                  <div className="nx-avatar-footer">
                    <span className="nx-muted">{avatarStatus}</span>
                    <button type="button" className="nx-btn is-primary" disabled={saving || !profile || !selectedAvatar || selectedAvatar === (profile.profileImageUrl ?? "")} onClick={() => void saveAvatar()}>
                      {saving ? "저장 중…" : "프로필 사진 저장"}
                    </button>
                  </div>
                </fieldset>
              )}
            </section>

            <section className="nx-panel nx-danger-zone" aria-labelledby="nx-reset-title">
              <div>
                <h2 id="nx-reset-title" className="nx-danger-title">계좌 초기화</h2>
                <p className="nx-muted">시작 자금으로 돌아가요. 보유 주식, 거래 기록, 투자 미션도 함께 초기화돼요.</p>
              </div>
              <button type="button" className="nx-btn is-danger" onClick={() => void resetAccount()} disabled={loading || resetting || !profile || !profile.resetAvailable}>
                {resetting ? "초기화 중…" : "계좌 초기화"}
              </button>
            </section>
          </div>
        </div>
      )}
    </div>
  );
}
