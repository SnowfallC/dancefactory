"""只收录运行文件、实际依赖和许可证，生成 Windows 发行压缩包。"""

import argparse
import io
from pathlib import Path
import re
import zipfile


rootFiles = ("舞形工坊.exe", "LICENSE", "THIRD_PARTY_NOTICES.md")
nativeFiles = ("libmpv-2.dll", "COPYRIGHT-mpv.txt", "LICENSE-GPL-mpv.txt", "LICENSE-LGPL-mpv.txt", "THIRD-PARTY-NOTICES.txt")


def readDependencies(jarBytes):
    with zipfile.ZipFile(io.BytesIO(jarBytes)) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    classPath = next((line[12:] for line in manifest.splitlines() if line.startswith("Class-Path: ")), "")
    dependencies = classPath.split()
    if not dependencies or any(not re.fullmatch(r"libs/[A-Za-z0-9_.-]+\.jar", name) for name in dependencies):
        raise ValueError("主程序依赖清单无效")
    return dependencies


def allowedAppFiles(version, dependencies):
    return {f"app/formation-tool-desktop-{version}.jar", "app/舞形工坊.cfg", "app/.jpackage.xml",
            "app/ffmpeg/ffmpeg.exe", *(f"app/{name}" for name in dependencies),
            *(f"app/native/mpv/{name}" for name in nativeFiles)}


def validateWindowsArchive(archive, version):
    names = archive.namelist()
    jars = [name for name in names if name.endswith(f"/app/formation-tool-desktop-{version}.jar")]
    if len(jars) != 1:
        raise ValueError("Windows 包的主程序不唯一或版本错误")
    appRoot = jars[0].split("/app/")[0]
    if "/" in appRoot:
        raise ValueError("Windows 包只允许一个顶层应用目录")
    allowed = set(rootFiles) | allowedAppFiles(version, readDependencies(archive.read(jars[0])))
    for name in names:
        if name.endswith("/"):
            continue
        relative = name.removeprefix(appRoot + "/")
        if relative == name or (relative not in allowed and not relative.startswith("runtime/")):
            raise ValueError(f"Windows 发行包包含非发行文件：{name}")
    return appRoot


def packageWindows(sourceDirectory, destination, version, projectDirectory):
    mainJar = sourceDirectory / f"app/formation-tool-desktop-{version}.jar"
    dependencies = readDependencies(mainJar.read_bytes())
    entries = {}
    for name in rootFiles:
        source = sourceDirectory / name
        entries[name] = source if source.is_file() else projectDirectory / name
    for name in allowedAppFiles(version, dependencies):
        source = sourceDirectory / name
        if name == "app/.jpackage.xml" and not source.exists():
            continue
        entries[name] = source
    for source in (sourceDirectory / "runtime").rglob("*"):
        if source.is_file():
            entries[source.relative_to(sourceDirectory).as_posix()] = source
    for name, source in entries.items():
        if not source.is_file():
            raise ValueError(f"发行文件缺失：{name}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    pending = destination.with_suffix(destination.suffix + ".pending")
    with zipfile.ZipFile(pending, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, source in sorted(entries.items()):
            archive.write(source, f"{sourceDirectory.name}/{name}")
    with zipfile.ZipFile(pending) as archive:
        validateWindowsArchive(archive, version)
    pending.replace(destination)
    return len(entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--version", required=True)
    arguments = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:\.\d+)?", arguments.version):
        parser.error("版本号必须为三段或四段数字")
    count = packageWindows(Path(arguments.source).resolve(), Path(arguments.output).resolve(), arguments.version, Path(__file__).resolve().parent.parent)
    print(f"发行包已生成，包含 {count} 个运行与许可文件：{arguments.output}")


if __name__ == "__main__":
    main()
