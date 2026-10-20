"""验证发行压缩包不会混入开发资料或未使用的依赖。"""

from pathlib import Path
import tempfile
import unittest
import zipfile
from package_windows_release import packageWindows, validateWindowsArchive, rootFiles, nativeFiles


class ReleasePackageTest(unittest.TestCase):
    def testExcludesDevelopmentFilesAndUnusedDependencies(self):
        with tempfile.TemporaryDirectory() as temporaryDirectory:
            root = Path(temporaryDirectory)
            app = root / "舞形工坊"
            for name in (*rootFiles, "app/舞形工坊.cfg", "app/libs/used.jar", "app/libs/unused.jar",
                         "app/ffmpeg/ffmpeg.exe", "runtime/lib/modules", "播放内核对比与验证.md",
                         "使用说明.txt", "CHANGELOG.md", "app/report.json",
                         *(f"app/native/mpv/{name}" for name in nativeFiles)):
                path = app / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"fixture")
            with zipfile.ZipFile(app / "app/formation-tool-desktop-1.1.6.jar", "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nClass-Path: libs/used.jar\r\n")
            output = root / "release.zip"
            packageWindows(app, output, "1.1.6", root)
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
                self.assertIn("舞形工坊/LICENSE", names)
                self.assertIn("舞形工坊/app/libs/used.jar", names)
                for unwanted in ("unused.jar", "report.json", "CHANGELOG.md", "使用说明.txt", "播放内核对比与验证.md"):
                    self.assertFalse(any(name.endswith(unwanted) for name in names))
            self.assertTrue((app / "播放内核对比与验证.md").exists())
            with zipfile.ZipFile(output, "a") as archive:
                archive.writestr("舞形工坊/verification.json", "{}")
            with zipfile.ZipFile(output) as archive:
                with self.assertRaisesRegex(ValueError, "非发行文件"):
                    validateWindowsArchive(archive, "1.1.6")


if __name__ == "__main__":
    unittest.main()
