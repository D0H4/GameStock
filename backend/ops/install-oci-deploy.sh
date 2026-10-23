#!/bin/bash
# One-time setup by the server administrator, never by the deployment key.
set -euo pipefail
[[ $EUID == 0 && $# == 1 && -f $1 ]] || { echo 'Run as root with a deployment public key file' >&2; exit 64; }
directory=$(cd -- "$(dirname -- "$0")" && pwd)
install -o root -g root -m 0644 "$directory/gamestock.service" /etc/systemd/system/gamestock.service
if ! id gamestock-deploy >/dev/null 2>&1; then
    useradd --system --no-create-home --home-dir /var/lib/gamestock-deploy --shell /bin/bash gamestock-deploy
fi
install -d -o root -g root -m 0755 /var/lib/gamestock-deploy /var/lib/gamestock-deploy/.ssh /usr/local/libexec
install -o root -g root -m 0755 "$directory/deploy-oci.sh" /usr/local/sbin/gamestock-deploy
install -o root -g root -m 0755 "$directory/oci-deploy-request.sh" /usr/local/bin/gamestock-deploy-request
install -o root -g root -m 0755 "$directory/oci-healthcheck.py" /usr/local/libexec/gamestock-healthcheck.py
public_key=$(cat -- "$1")
[[ $public_key =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+(\ .*)?$ ]] || exit 65
printf 'restrict,command="/usr/local/bin/gamestock-deploy-request" %s\n' "$public_key" > /var/lib/gamestock-deploy/.ssh/authorized_keys
chmod 0644 /var/lib/gamestock-deploy/.ssh/authorized_keys
printf 'gamestock-deploy ALL=(root) NOPASSWD: /usr/local/sbin/gamestock-deploy *\n' > /etc/sudoers.d/gamestock-deploy
chmod 0440 /etc/sudoers.d/gamestock-deploy
visudo -cf /etc/sudoers.d/gamestock-deploy
chown root:gamestock /opt/gamestock /opt/gamestock/releases
chmod 0750 /opt/gamestock /opt/gamestock/releases
chown root:gamestock /opt/gamestock/releases/gamestock-backend-production.jar
chmod 0640 /opt/gamestock/releases/gamestock-backend-production.jar
# The production service must depend on its production credentials, not old staging files.
sed -i 's|ConditionPathExists=/etc/gamestock/staging.env|ConditionPathExists=/etc/gamestock/production.env|' /etc/systemd/system/gamestock.service
systemctl daemon-reload
restorecon -RF /var/lib/gamestock-deploy /usr/local/bin/gamestock-deploy-request /usr/local/sbin/gamestock-deploy /usr/local/libexec/gamestock-healthcheck.py
echo 'Restricted OCI deployment account installed.'
