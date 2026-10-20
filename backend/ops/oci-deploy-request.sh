#!/bin/bash
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
if [[ ${SSH_ORIGINAL_COMMAND:-} =~ ^deploy\ ([a-f0-9]{40})\ ([a-f0-9]{64})$ ]]; then
    exec sudo -n /usr/local/sbin/gamestock-deploy "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
fi
echo 'Only a validated GameStock deployment request is allowed.' >&2
exit 64
