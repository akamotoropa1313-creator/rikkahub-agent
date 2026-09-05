import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release_tools', Path(__file__).with_name('release_tools.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.old_cwd = Path.cwd()
        os.chdir(self.tmp.name)
        self.addCleanup(os.chdir, self.old_cwd)
        self.sha = 'a' * 40
        self.cert = 'b' * 64
        self.env = patch.dict(os.environ, EXPECTED_COMMIT=self.sha, GITHUB_SHA=self.sha,
                              RELEASE_CERT_SHA256=self.cert, ANDROID_HOME='/sdk',
                              LAST_RELEASE_VERSION_CODE='183', clear=False)
        self.env.start()
        self.addCleanup(self.env.stop)
        Path('release').mkdir()
        self.metadata = dict(application_id='excp.rikkahub', version_code=184,
                             version_name='2.4.10-akaro.1-alpha.1', tag='v2.4.10-akaro.1-alpha.1', prerelease=True)
        Path('release/baseline.json').write_text(json.dumps(self.metadata))
        Path('app').mkdir()
        Path('app/build.gradle.kts').write_text('applicationId = "excp.rikkahub"\nversionCode = 184\nversionName = "2.4.10-akaro.1-alpha.1"\n')
        Path('apks').mkdir()
        for abi in ['arm64-v8a', 'x86_64', 'universal']:
            Path(f'apks/app-{abi}-release.apk').write_bytes(abi.encode())
        self.debuggable = False
        self.signer = self.cert

    def command(self, *args):
        if args[0] == 'git':
            return '' if args[1] in ('status', 'diff') else self.sha + '\n'
        if args[0].endswith('/aapt'):
            name = Path(args[-1]).name
            abi = "'arm64-v8a' 'x86_64'" if 'universal' in name else "'arm64-v8a'" if 'arm64' in name else "'x86_64'"
            return "package: name='excp.rikkahub' versionCode='184' versionName='2.4.10-akaro.1-alpha.1'\nnative-code: " + abi + '\n' + ('application-debuggable\n' if self.debuggable else '')
        return 'Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: ' + self.signer + '\n'

    def test_exact_source_and_three_verified_abis(self):
        with patch.object(release, 'run', side_effect=self.command):
            release.check_source()
            release.verify_apks('apks', 'out')
        result = json.loads(Path('out/provenance.json').read_text())
        self.assertEqual(self.sha, result['source_sha'])
        self.assertEqual(3, len(result['artifacts']))
        self.assertTrue(all(hashlib.sha256((Path('out') / a['name']).read_bytes()).hexdigest() == a['sha256'] for a in result['artifacts']))

    def test_rejects_wrong_dispatch_commit(self):
        with patch.object(release, 'run', side_effect=self.command), patch.dict(os.environ, GITHUB_SHA='c' * 40):
            with self.assertRaisesRegex(SystemExit, 'exact candidate'):
                release.check_source()

    def test_rejects_version_code_reuse(self):
        with patch.object(release, 'run', side_effect=self.command), patch.dict(os.environ, LAST_RELEASE_VERSION_CODE='184'):
            with self.assertRaisesRegex(SystemExit, 'increase'):
                release.check_source()

    def test_rejects_modified_tracked_source(self):
        def dirty(*args):
            return 'app/src/main/changed.kt\n' if args[:2] == ('git', 'diff') else self.command(*args)
        with patch.object(release, 'run', side_effect=dirty):
            with self.assertRaisesRegex(SystemExit, 'Tracked source differs'):
                release.verify_apks('apks', 'out')

    def test_rejects_missing_or_different_signing_identity(self):
        with patch.object(release, 'run', side_effect=self.command):
            with patch.dict(os.environ, RELEASE_CERT_SHA256=''):
                with self.assertRaisesRegex(SystemExit, 'SIGNING BLOCKER'):
                    release.verify_apks('apks', 'out')
            self.signer = 'c' * 64
            with self.assertRaisesRegex(SystemExit, 'identity mismatch'):
                release.verify_apks('apks', 'out')

    def test_rejects_debuggable_release_and_benchmark_filename(self):
        with patch.object(release, 'run', side_effect=self.command):
            self.debuggable = True
            with self.assertRaisesRegex(SystemExit, 'Debuggable'):
                release.verify_apks('apks', 'out')
            self.debuggable = False
            Path('apks/app-arm64-v8a-release.apk').rename('apks/app-arm64-v8a-benchmark.apk')
            with self.assertRaisesRegex(SystemExit, 'variant'):
                release.verify_apks('apks', 'out')

    def test_requires_device_attestation_for_identical_apk_bytes(self):
        with patch.object(release, 'run', side_effect=self.command):
            release.verify_apks('apks', 'out')
            manifest = json.loads(Path('out/provenance.json').read_text())
            arm = next(a for a in manifest['artifacts'] if a['abis'] == ['arm64-v8a'])
            with patch.dict(os.environ, DEVICE_VALIDATED='false', DEVICE_APK_SHA256=arm['sha256']):
                with self.assertRaisesRegex(SystemExit, 'DEVICE VALIDATION'):
                    release.verify_device('out')
            with patch.dict(os.environ, DEVICE_VALIDATED='true', DEVICE_APK_SHA256='0' * 64):
                with self.assertRaisesRegex(SystemExit, 'Device-tested APK differs'):
                    release.verify_device('out')
            with patch.dict(os.environ, DEVICE_VALIDATED='true', DEVICE_APK_SHA256=arm['sha256']):
                release.verify_device('out')
                (Path('out') / arm['name']).write_bytes(b'tampered')
                with self.assertRaisesRegex(SystemExit, 'checksum mismatch'):
                    release.verify_device('out')


if __name__ == '__main__':
    unittest.main()
