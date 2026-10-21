import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { createReadStream, existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { parseArgs } from 'node:util';

const projectDirectory = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const publicBaseUrl = 'https://pub-02ac16656b17473f97b1b838e1bc546d.r2.dev';
const maxManifestBytes = 1024 * 1024;

export function readTrustedKeys(root = projectDirectory) {
  const desktop = readFileSync(join(root, 'src/main/java/com/formationtool/desktop/update/UpdateManifestVerifier.java'), 'utf8');
  const android = readFileSync(join(root, 'app/src/main/java/com/formationtool/app/update/UpdateManifestVerifier.kt'), 'utf8');
  const keys = ['CURRENT_PUBLIC_KEY_BASE64', 'PUBLIC_KEY_BASE64'].map(name => {
    const pattern = new RegExp(`\\b${name}\\s*=\\s*"([^"]+)"`);
    const desktopKey = desktop.match(pattern)?.[1];
    if (!desktopKey || desktopKey !== android.match(pattern)?.[1]) throw new Error('两端更新公钥不一致');
    return Buffer.from(desktopKey, 'base64');
  });
  return keys;
}

export function signManifest(payload, privateKey, trustedKeys) {
  const key = createPrivateKey(privateKey);
  const publicDer = createPublicKey(key).export({ type: 'spki', format: 'der' });
  if (!publicDer.equals(trustedKeys[0])) throw new Error('更新私钥与当前客户端公钥不匹配，停止发布');
  const payloadBytes = Buffer.from(JSON.stringify(payload));
  return Buffer.from(JSON.stringify({
    schemaVersion: 2,
    signatureAlgorithm: 'SHA256withRSA',
    payload: payloadBytes.toString('base64'),
    signature: sign('RSA-SHA256', payloadBytes, key).toString('base64'),
  }, null, 2) + '\n');
}

export function verifyManifest(bytes, trustedKeys) {
  if (bytes.length > maxManifestBytes) throw new Error('更新清单过大');
  const envelope = JSON.parse(bytes.toString('utf8'));
  if (envelope.schemaVersion !== 2 || envelope.signatureAlgorithm !== 'SHA256withRSA') throw new Error('更新清单格式无效');
  const payloadBytes = Buffer.from(envelope.payload, 'base64');
  const signature = Buffer.from(envelope.signature, 'base64');
  if (payloadBytes.length > maxManifestBytes || !trustedKeys.some(der => verify('RSA-SHA256', payloadBytes,
    createPublicKey({ key: der, type: 'spki', format: 'der' }), signature))) throw new Error('更新清单签名验证失败');
  const payload = JSON.parse(payloadBytes.toString('utf8'));
  if (payload.appId !== 'dancefactory') throw new Error('更新清单所属应用不正确');
  return payload;
}

export async function hashFile(path) {
  const hash = createHash('sha256');
  let size = 0;
  for await (const chunk of createReadStream(path)) { hash.update(chunk); size += chunk.length; }
  if (size <= 0 || size > 2 * 1024 ** 3) throw new Error('安装包大小无效');
  return { sha256: hash.digest('hex'), size };
}

export function compareVersions(left, right) {
  if (![left, right].every(value => /^\d+\.\d+\.\d+(?:\.\d+)?$/.test(value))) throw new Error('版本号必须为三段或四段数字');
  const a = left.split('.').map(Number), b = right.split('.').map(Number);
  return Math.sign(a[0] - b[0] || a[1] - b[1] || a[2] - b[2] || (a[3] ?? 0) - (b[3] ?? 0));
}

async function fetchResponse(url) {
  if (new URL(url).protocol !== 'https:') throw new Error('发布地址必须使用 HTTPS');
  return fetch(url, {
    headers: { 'User-Agent': 'DanceFactory-Updater/1.1', 'Cache-Control': 'no-cache' },
    redirect: 'error', signal: AbortSignal.timeout(300000),
  });
}

async function readRemoteManifest(url) {
  const response = await fetchResponse(url);
  if (!response.ok) throw new Error(`无法读取线上清单：HTTP ${response.status}`);
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > maxManifestBytes) throw new Error('线上清单过大');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function checkRemoteArtifact(artifact, allowMissing = false) {
  const response = await fetchResponse(artifact.downloadUrl);
  if (allowMissing && response.status === 404) { await response.body?.cancel(); return false; }
  if (!response.ok) throw new Error(`无法下载 ${artifact.fileName}：HTTP ${response.status}`);
  const hash = createHash('sha256');
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > artifact.size) throw new Error(`线上安装包过大：${artifact.fileName}`);
    hash.update(chunk);
  }
  if (size !== artifact.size || hash.digest('hex') !== artifact.sha256.toLowerCase()) {
    throw new Error(`线上已存在不同内容的安装包，禁止覆盖：${artifact.fileName}`);
  }
  return true;
}

function runWrangler(args) {
  const npxCli = join(dirname(process.execPath), 'node_modules/npm/bin/npx-cli.js');
  if (!existsSync(npxCli)) throw new Error('找不到 npm npx-cli.js，请使用标准 Node.js 安装');
  execFileSync(process.execPath, [npxCli, '--yes', 'wrangler@4.151.2', ...args], {
    cwd: projectDirectory, stdio: 'inherit', windowsHide: true,
    env: { ...process.env, CI: '1', WRANGLER_SEND_METRICS: 'false' },
  });
}

// 安装包使用版本化地址；只在所有文件通过公网下载校验后更新 latest.json。
export async function publishRelease(plan, store) {
  const original = await store.readManifest();
  const remote = verifyManifest(original, plan.trustedKeys);
  const comparison = compareVersions(plan.payload.version, remote.version);
  if (comparison < 0 || (comparison > 0 && plan.payload.versionCode <= remote.versionCode)) {
    throw new Error('新版本号必须高于线上版本，禁止回退');
  }
  if (comparison === 0) {
    if (JSON.stringify(remote.artifacts) === JSON.stringify(plan.payload.artifacts)) {
      for (const artifact of Object.values(plan.payload.artifacts)) await store.checkArtifact(artifact, false);
      return '当前版本已发布，校验通过';
    }
    if (!plan.replaceManifest || plan.payload.versionCode !== remote.versionCode) throw new Error('相同版本已发布不同内容');
    // 修正同版本发行包时使用新地址，避免用户缓存旧文件后出现哈希冲突。
    for (const [platform, artifact] of Object.entries(plan.payload.artifacts)) {
      const previous = remote.artifacts[platform];
      if (previous?.downloadUrl === artifact.downloadUrl && previous.sha256 !== artifact.sha256) {
        throw new Error('同版本更换安装包必须使用新的下载地址');
      }
    }
  }
  for (const [platform, artifact] of Object.entries(plan.payload.artifacts)) {
    if (!await store.checkArtifact(artifact, true)) {
      await store.put(`dancefactory/${platform}/${artifact.fileName}`, plan.paths[platform],
        platform === 'windows' ? 'application/zip' : 'application/vnd.android.package-archive',
        'public, max-age=31536000, immutable');
      await store.checkArtifact(artifact, false);
    }
  }
  await store.put(`dancefactory/updates/${plan.payload.version}.json`, plan.manifestPath, 'application/json', 'no-store');
  if (!(await store.readManifest()).equals(original)) throw new Error('发布期间线上清单发生变化，请重新检查');
  await store.put('dancefactory/updates/latest.json', plan.manifestPath, 'application/json', 'no-store');
  const published = verifyManifest(await store.readManifest(), plan.trustedKeys);
  if (JSON.stringify(published) !== JSON.stringify(plan.payload)) throw new Error('线上清单与发布内容不一致');
  return '发布成功：线上清单与两端安装包均已校验';
}

async function main() {
  const { values } = parseArgs({ options: {
    version: { type: 'string' }, 'release-dir': { type: 'string' }, bucket: { type: 'string', default: 'dancefactory' },
    'private-key': { type: 'string', default: join(projectDirectory, '.signing/update-private-key.pem') },
    publish: { type: 'boolean', default: false }, help: { type: 'boolean', default: false },
    'windows-file': { type: 'string' }, 'replace-manifest': { type: 'boolean', default: false },
  } });
  if (values.help) {
    console.log('node tools/publish-update.mjs [--version 1.1.6] [--release-dir 路径] [--private-key 路径] [--publish]\n同版本修正：--windows-file 新文件名.zip --replace-manifest\n默认只生成本地签名清单；--publish 才上传 R2 并切换线上版本。先运行 npx wrangler@4.151.2 login。');
    return;
  }
  const pom = readFileSync(join(projectDirectory, 'pom.xml'), 'utf8');
  const version = values.version ?? pom.match(/<artifactId>formation-tool-desktop<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1];
  compareVersions(version, version);
  const gradle = readFileSync(join(projectDirectory, 'app/build.gradle'), 'utf8');
  const versionCode = Number(gradle.match(/\bversionCode\s+(\d+)/)?.[1]);
  if (!Number.isSafeInteger(versionCode) || versionCode < 1 || gradle.match(/\bversionName\s+"([^"]+)"/)?.[1] !== version) {
    throw new Error('Android 与桌面版本不一致');
  }
  const releaseDirectory = resolve(values['release-dir'] ?? join(projectDirectory, `target/release-${version}`));
  const artifacts = {}, paths = {};
  for (const [platform, suffix] of [['windows', 'zip'], ['android', 'release.apk']]) {
    const fileName = platform === 'windows' && values['windows-file'] ? values['windows-file'] :
      `dancefactory-${platform}-${version}.${suffix}`.replace('.release.apk', '-release.apk');
    if (!/^[A-Za-z0-9][A-Za-z0-9_.-]*\.(zip|apk)$/.test(fileName)) throw new Error('安装包文件名无效');
    paths[platform] = join(releaseDirectory, fileName);
    artifacts[platform] = { fileName, downloadUrl: `${publicBaseUrl}/dancefactory/${platform}/${fileName}`, ...await hashFile(paths[platform]) };
  }
  const verificationReport = JSON.parse(execFileSync('python', [join(projectDirectory, 'tools/verify-release-artifacts.py'),
    '--release-dir', releaseDirectory, '--version', version, '--windows-file', artifacts.windows.fileName], {
    encoding: 'utf8', windowsHide: true, env: { ...process.env, PYTHONIOENCODING: 'utf-8' },
  }));
  writeFileSync(join(releaseDirectory, 'release-artifact-verification.json'), JSON.stringify(verificationReport, null, 2) + '\n');
  for (const artifact of Object.values(artifacts)) {
    if (verificationReport.artifacts.find(item => item.name === artifact.fileName)?.sha256 !== artifact.sha256) {
      throw new Error('安装包在验证期间发生变化');
    }
  }
  const changelog = readFileSync(join(projectDirectory, 'CHANGELOG.md'), 'utf8');
  const section = changelog.split(`## ${version} - `)[1]?.split(/\r?\n## /)[0];
  const notes = section?.split(/\r?\n/).filter(line => line.startsWith('- ')).map(line => line.slice(2));
  if (!notes?.length) throw new Error('当前版本的更新说明缺失');
  const trustedKeys = readTrustedKeys();
  const payload = { schemaVersion: 1, appId: 'dancefactory', version, versionCode,
    publishedAt: new Date().toISOString(), mandatory: false, notes, artifacts };
  const manifest = signManifest(payload, readFileSync(resolve(values['private-key'])), trustedKeys);
  verifyManifest(manifest, trustedKeys);
  mkdirSync(releaseDirectory, { recursive: true });
  const manifestPath = join(releaseDirectory, 'latest.json');
  writeFileSync(manifestPath, manifest);
  console.log(`本地签名清单已生成：${manifestPath}`);
  if (!values.publish) { console.log('尚未上传；确认产物后加 --publish 发布。'); return; }
  let firstRead = true;
  const store = {
    readManifest: async () => {
      const bytes = await readRemoteManifest(`${publicBaseUrl}/dancefactory/updates/latest.json`);
      if (firstRead) {
        verifyManifest(bytes, trustedKeys);
        writeFileSync(join(releaseDirectory, `latest-before-publish-${Date.now()}.json`), bytes);
        firstRead = false;
      }
      return bytes;
    },
    checkArtifact: checkRemoteArtifact,
    put: async (objectKey, file, contentType, cacheControl) => runWrangler(['r2', 'object', 'put',
      `${values.bucket}/${objectKey}`, '--file', file, '--remote', '--content-type', contentType, '--cache-control', cacheControl]),
  };
  console.log(await publishRelease({ payload, paths, manifestPath, trustedKeys, replaceManifest: values['replace-manifest'] }, store));
  writeFileSync(join(releaseDirectory, 'r2-publication-verification.json'), JSON.stringify({
    version, bucket: values.bucket, manifestUrl: `${publicBaseUrl}/dancefactory/updates/latest.json`,
    verifiedAt: new Date().toISOString(), manifestSignatureVerified: true, publicDownloadsVerified: true, artifacts,
  }, null, 2) + '\n');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(`发布失败：${error.message}`); process.exitCode = 1; });
}
