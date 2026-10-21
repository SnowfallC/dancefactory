"""发布前验证两端安装包内容、安卓证书与更新公钥。"""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
from package_windows_release import validateWindowsArchive


def hashFile(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verifyArtifacts(releaseDirectory, version, windowsFileName=None):
    projectDirectory = Path(__file__).resolve().parent.parent
    sourceText = (projectDirectory / "src/main/java/com/formationtool/desktop/update/UpdateManifestVerifier.java").read_text(encoding="utf-8")
    keyText = re.search(r'CURRENT_PUBLIC_KEY_BASE64\s*=\s*"([^"]+)"', sourceText).group(1)
    keyBytes = keyText.encode("ascii")
    keyFingerprint = hashlib.sha256(base64.b64decode(keyText)).hexdigest()
    windowsPath = releaseDirectory / (windowsFileName or f"dancefactory-windows-{version}.zip")
    androidPath = releaseDirectory / f"dancefactory-android-{version}-release.apk"

    with zipfile.ZipFile(windowsPath) as archive:
        validateWindowsArchive(archive, version)
        jarNames = [name for name in archive.namelist() if name.endswith(f"/app/formation-tool-desktop-{version}.jar")]
        if len(jarNames) != 1:
            raise ValueError("Windows 压缩包的主程序不唯一或版本不正确")
        appRoot = jarNames[0].split("/app/")[0]
        requiredFiles = ["舞形工坊.exe", "runtime/bin/server/jvm.dll", "app/native/mpv/libmpv-2.dll", "app/ffmpeg/ffmpeg.exe"]
        if any(f"{appRoot}/{name}" not in archive.namelist() for name in requiredFiles):
            raise ValueError("Windows 包缺少启动程序、运行时或播放组件")
        with zipfile.ZipFile(io.BytesIO(archive.read(jarNames[0]))) as jar:
            classBytes = jar.read("com/formationtool/desktop/update/UpdateManifestVerifier.class")
            if keyBytes not in classBytes:
                raise ValueError("Windows 包未包含当前更新公钥，请重新打包")

    with zipfile.ZipFile(androidPath) as archive:
        dexNames = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        if not any(keyBytes in archive.read(name) for name in dexNames):
            raise ValueError("Android 包未包含当前更新公钥，请重新打包")

    sdkDirectory = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or str(Path.home() / "AppData/Local/Android/Sdk"))
    toolsDirectories = [path for path in (sdkDirectory / "build-tools").iterdir() if re.fullmatch(r"\d+(\.\d+)+", path.name)]
    toolsDirectory = max(toolsDirectories, key=lambda path: tuple(map(int, path.name.split("."))))
    javaPath = Path(os.environ["JAVA_HOME"]) / "bin/java.exe" if os.environ.get("JAVA_HOME") else shutil.which("java")
    certificateResult = subprocess.run([str(javaPath), "-jar", str(toolsDirectory / "lib/apksigner.jar"), "verify", "--print-certs", str(androidPath)], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True)
    certificateMatch = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", certificateResult.stdout)
    expectedCertificate = "4044fbddf1338ec715f340a0a300a52f9ed497b5f325d60e6a0c7c510fd5c61c"
    if not certificateMatch or certificateMatch.group(1) != expectedCertificate:
        raise ValueError("Android 签名与原正式版证书不一致")
    badging = subprocess.run([str(toolsDirectory / "aapt.exe"), "dump", "badging", str(androidPath)], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True).stdout
    packageMatch = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    versionCode = int(re.search(r"\bversionCode\s+(\d+)", (projectDirectory / "app/build.gradle").read_text(encoding="utf-8")).group(1))
    if not packageMatch or packageMatch.groups() != ("com.formationtool.app", str(versionCode), version):
        raise ValueError("Android 包名或版本信息不正确")

    return {
        "version": version, "versionCode": versionCode, "applicationId": "com.formationtool.app",
        "apkSignatureVerified": True, "certificateSha256": expectedCertificate, "legacyCertificateMatches": True,
        "updateTrustKeySha256": keyFingerprint,
        "windowsUpdateKeyEmbedded": True, "androidUpdateKeyEmbedded": True,
        "windowsReleaseFilesOnly": True,
        "artifacts": [{"name": path.name, "bytes": path.stat().st_size, "sha256": hashFile(path)} for path in [windowsPath, androidPath]],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--windows-file")
    arguments = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:\.\d+)?", arguments.version):
        parser.error("版本号必须为三段或四段数字")
    if arguments.windows_file and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.zip", arguments.windows_file):
        parser.error("Windows 文件名无效")
    result = verifyArtifacts(Path(arguments.release_dir).resolve(), arguments.version, arguments.windows_file)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
