#!/bin/bash
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 027
RELEASES=/opt/gamestock/releases
STATE=/var/lib/gamestock-deploy
LOCK=/run/lock/gamestock-deploy.lock
HEALTHCHECK=/usr/local/libexec/gamestock-healthcheck.py
SERVICE=gamestock
ATTEMPTS=30

[[ $# == 2 && $1 =~ ^[a-f0-9]{40}$ && $2 =~ ^[a-f0-9]{64}$ ]] || exit 64
revision=$1
digest=$2
exec 9>"$LOCK"
flock -w 600 9 || { echo 'Deployment lock timeout' >&2; exit 75; }
active="$RELEASES/gamestock-backend-production.jar"
candidate="$RELEASES/$revision-$digest.jar"
upload=$(mktemp "$STATE/upload.XXXXXX")
switch_pending=0

ready() {
    systemctl is-active --quiet "$SERVICE" && python3 "$HEALTHCHECK" http://127.0.0.1:8081 >/dev/null 2>&1
}
wait_ready() {
    local deadline=$((SECONDS + 90))
    for ((attempt=0; attempt<ATTEMPTS; attempt++)); do
        (( SECONDS < deadline )) || break
        if ready; then return 0; fi
        sleep 2
    done
    return 1
}
activate() {
    ln -sfn -- "$1" "$RELEASES/.active-next"
    mv -Tf -- "$RELEASES/.active-next" "$active"
}
finish() {
    result=$?
    trap - EXIT HUP INT TERM
    rm -f -- "$upload"
    if (( switch_pending )); then
        echo 'DEPLOY_FAILED: restoring previous artifact' >&2
        if ! systemctl stop "$SERVICE" || systemctl is-active --quiet "$SERVICE"; then
            echo 'ROLLBACK_FAILED: matching engine could not be stopped' >&2
            exit 1
        fi
        activate "$previous"
        if systemctl start "$SERVICE" && wait_ready; then
            echo 'ROLLBACK_OK' >&2
        else
            echo 'ROLLBACK_FAILED: administrator attention required' >&2
        fi
        exit 1
    fi
    exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

# SSH stdin carries the JAR. Bound input size before touching the running service.
head -c 209715201 > "$upload"
[[ $(stat -c %s "$upload") -le 209715200 ]] || { echo 'Artifact exceeds 200 MiB' >&2; exit 65; }
actual=$(sha256sum "$upload" | cut -d ' ' -f 1)
[[ $actual == "$digest" ]] || { echo 'Artifact checksum mismatch' >&2; exit 65; }
unzip -tq "$upload" >/dev/null || { echo 'Invalid JAR archive' >&2; exit 65; }
previous=$(readlink -f "$active")
[[ -f $previous && $previous == "$RELEASES/"* ]] || { echo 'Missing previous artifact' >&2; exit 66; }
if [[ ! -L $active ]]; then
    previous_digest=$(sha256sum "$active" | cut -d ' ' -f 1)
    previous="$RELEASES/legacy-$previous_digest.jar"
    install -o root -g gamestock -m 0640 "$active" "$previous"
fi
if [[ $(readlink -f "$active") == "$candidate" ]] && ready; then
    printf '%s\n' "$revision" > "$STATE/deployed-revision"
    echo "DEPLOY_ALREADY_CURRENT $revision"
    exit 0
fi
if [[ -e $candidate ]]; then
    [[ $(sha256sum "$candidate" | cut -d ' ' -f 1) == "$digest" ]] || exit 65
else
    install -o root -g gamestock -m 0640 "$upload" "$candidate"
fi

switch_pending=1
systemctl stop "$SERVICE"
if systemctl is-active --quiet "$SERVICE"; then
    echo 'Previous matching engine did not stop' >&2
    exit 70
fi
activate "$candidate"
systemctl start "$SERVICE"
wait_ready || { echo 'Readiness deadline exceeded' >&2; exit 70; }
printf '%s\n' "$previous" > "$STATE/previous-artifact"
printf '%s\n' "$revision" > "$STATE/deployed-revision"
switch_pending=0
echo "DEPLOY_OK $revision $digest"

# Retain five recent managed JARs plus the active and immediate rollback versions.
python3 - "$RELEASES" "$candidate" "$previous" <<'PY'
from pathlib import Path
import re
import sys
directory = Path(sys.argv[1])
managed = [p for p in directory.iterdir() if not p.is_symlink() and p.is_file()
           and re.fullmatch(r'(?:legacy-[a-f0-9]{64}|[a-f0-9]{40}-[a-f0-9]{64})\.jar', p.name)]
managed.sort(key=lambda p: p.stat().st_mtime, reverse=True)
keep = set(managed[:5]) | {Path(sys.argv[2]), Path(sys.argv[3])}
for artifact in managed:
    if artifact not in keep:
        artifact.unlink()
PY
