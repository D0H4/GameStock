#!/usr/bin/env fish
# 로컬 인증 계정을 재시작 전후로 유지한다.
cd (dirname (status filename))/..; or exit 1
set auth_data "$PWD/.firebase/emulator-data"
set auth_args emulators:start --only auth --project local-development --import "$auth_data" --export-on-exit "$auth_data"

if type -q firebase
    firebase $auth_args
else
    set cached_cli (path filter -f $HOME/.npm/_npx/*/node_modules/firebase-tools/lib/bin/firebase.js)
    if test (count $cached_cli) -gt 0
        node "$cached_cli[1]" $auth_args
    else
        echo "Firebase CLI가 필요합니다. 설치 후 다시 실행해 주세요." >&2
        exit 1
    end
end
