"""Exercise the real deployment script with an isolated service and filesystem."""
import hashlib
import io
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
import zipfile

OPS = Path(__file__).resolve().parent
REVISION = 'a' * 40

def artifact(healthy=True):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        archive.writestr('healthy.txt', 'yes' if healthy else 'no')
    return output.getvalue()

class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='gamestock-deploy-test-')
        self.root = Path(self.temporary.name)
        self.releases = self.root / 'releases'
        self.state = self.root / 'state'
        self.commands = self.root / 'bin'
        for directory in (self.releases, self.state, self.commands):
            directory.mkdir()
        self.active = self.releases / 'gamestock-backend-production.jar'
        self.original = artifact()
        self.active.write_bytes(self.original)
        (self.root / 'running').touch()
        systemctl = '''#!/usr/bin/python3
from pathlib import Path
import sys
root=Path(ROOT)
running=root/'running'
command=sys.argv[1]
if command == 'is-active':
    sys.exit(0 if running.exists() else 3)
with (root/'events').open('a') as events: events.write(command+'\\n')
if command == 'stop': running.unlink(missing_ok=True)
elif command == 'start':
    if running.exists(): raise RuntimeError('Concurrent matching engine')
    running.touch()
else: raise RuntimeError('Unexpected systemctl command')
'''.replace('ROOT', repr(str(self.root)))
        self.stub('systemctl', systemctl)
        self.stub('sleep', '#!/bin/sh\nexit 0\n')
        self.stub('install', '''#!/usr/bin/python3
import subprocess,sys
args=sys.argv[1:]
for flag in ('-o','-g'):
    if flag in args:
        index=args.index(flag)
        del args[index:index+2]
sys.exit(subprocess.call(['/usr/bin/install',*args]))
''')
        health = self.root / 'health.py'
        health.write_text('import zipfile\nfrom pathlib import Path\n'
                          + 'p=Path(' + repr(str(self.active)) + ')\n'
                          + "assert zipfile.ZipFile(p).read('healthy.txt')==b'yes'\n")
        script = (OPS / 'deploy-oci.sh').read_text()
        replacements = {
            'export PATH=/usr/sbin:/usr/bin:/sbin:/bin': 'export PATH=' + shlex.quote(str(self.commands)) + ':/usr/bin:/bin',
            'RELEASES=/opt/gamestock/releases': 'RELEASES=' + shlex.quote(str(self.releases)),
            'STATE=/var/lib/gamestock-deploy': 'STATE=' + shlex.quote(str(self.state)),
            'LOCK=/run/lock/gamestock-deploy.lock': 'LOCK=' + shlex.quote(str(self.root / 'lock')),
            'HEALTHCHECK=/usr/local/libexec/gamestock-healthcheck.py': 'HEALTHCHECK=' + shlex.quote(str(health)),
            'ATTEMPTS=30': 'ATTEMPTS=2',
        }
        for original, replacement in replacements.items():
            assert original in script
            script = script.replace(original, replacement)
        self.script = self.root / 'deploy.sh'
        self.script.write_text(script)

    def tearDown(self):
        self.temporary.cleanup()

    def stub(self, name, content):
        path = self.commands / name
        path.write_text(content)
        path.chmod(0o755)

    def deploy(self, data, digest=None):
        return subprocess.run(['bash', str(self.script), REVISION, digest or hashlib.sha256(data).hexdigest()],
                              input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_success_stops_previous_engine_before_starting_new_one(self):
        data = artifact()
        result = self.deploy(data)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertTrue(self.active.is_symlink())
        self.assertEqual(self.active.read_bytes(), data)
        self.assertEqual((self.root / 'events').read_text(), 'stop\nstart\n')
        self.assertEqual((self.state / 'deployed-revision').read_text().strip(), REVISION)

    def test_unhealthy_release_restores_previous_jar_and_reports_failure(self):
        result = self.deploy(artifact(False))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b'ROLLBACK_OK', result.stderr)
        self.assertEqual(self.active.read_bytes(), self.original)
        self.assertTrue((self.root / 'running').exists())
        self.assertFalse((self.state / 'deployed-revision').exists())
        self.assertEqual((self.root / 'events').read_text(), 'stop\nstart\nstop\nstart\n')

    def test_checksum_mismatch_never_stops_running_service(self):
        result = self.deploy(artifact(), '0' * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'events').exists())
        self.assertEqual(self.active.read_bytes(), self.original)

    def test_invalid_archive_never_stops_running_service(self):
        result = self.deploy(b'not a jar')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'events').exists())

    def test_deployment_key_rejects_shell_commands(self):
        import os
        for command in ('whoami', 'deploy ' + REVISION + ' ' + '0' * 64 + '; id'):
            result = subprocess.run(['bash', str(OPS / 'oci-deploy-request.sh')],
                                    env={**os.environ, 'SSH_ORIGINAL_COMMAND': command}, capture_output=True)
            self.assertEqual(result.returncode, 64)

if __name__ == '__main__':
    unittest.main(verbosity=2)
