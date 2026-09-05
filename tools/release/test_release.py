import tempfile
from pathlib import Path
import unittest
import zipfile

from build_release import guide_markdown, verify_jar, version_from_tag


class ReleaseTests(unittest.TestCase):
    def test_tag_versions_and_prereleases(self):
        for tag in ('v0.1.0-rc3', 'v0.1.0', 'v1.2.3-beta.2'):
            self.assertEqual(version_from_tag(tag), tag[1:])
        for tag in ('main', '0.1.0', 'v01.2.3', 'v1.2.3-01', 'v1.2.3\nBAD=1', 'v1.2.3;echo bad'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version_from_tag(tag)

    def test_readme_uses_release_version_and_jar_name(self):
        text = guide_markdown('0.1.0-rc3')
        self.assertIn('MODの配布版：**0.1.0-rc3**', text)
        self.assertIn('mcmcp-neoforge-26.2-0.1.0-rc3.jar', text)
        self.assertNotIn('2026-09-05 r4', text)
        self.assertNotIn('0.1.0-SNAPSHOT', text)

    def test_wrong_version_license_and_harness_are_rejected_before_packaging(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / 'mod.jar'
            for version, license_name, extra, valid in (
                ('0.1.0-rc3', 'MPL-2.0', None, True),
                ('0.1.0-SNAPSHOT', 'MPL-2.0', None, False),
                ('0.1.0-rc3', 'All Rights Reserved', None, False),
                ('0.1.0-rc3', 'MPL-2.0', 'dev/aod/mcmcp/fixture/Leaked.class', False),
                ('0.1.0-rc3', 'MPL-2.0', 'dev/aod/mcmcp/adminbridge/Leaked.class', False),
            ):
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr('META-INF/neoforge.mods.toml',
                                     f'license="{license_name}"\n[[mods]]\nmodId="mcmcp"\nversion="{version}"\n')
                    if extra:
                        archive.writestr(extra, b'fixture')
                if valid:
                    verify_jar(jar, '0.1.0-rc3')
                else:
                    with self.assertRaises(ValueError):
                        verify_jar(jar, '0.1.0-rc3')


if __name__ == '__main__':
    unittest.main()
