import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { readTrustedKeys, signManifest, verifyManifest, compareVersions, publishRelease } from './publish-update.mjs';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const testPrivateKey = privateKey.export({ type: 'pkcs8', format: 'pem' });
const trustedKeys = [publicKey.export({ type: 'spki', format: 'der' })];
const artifacts = {
  windows: { fileName: 'windows.zip', downloadUrl: 'https://example.com/windows.zip', sha256: 'a'.repeat(64), size: 100 },
  android: { fileName: 'android.apk', downloadUrl: 'https://example.com/android.apk', sha256: 'b'.repeat(64), size: 100 },
};
const oldPayload = { appId: 'dancefactory', version: '1.1.5', versionCode: 5, artifacts: {} };
const newPayload = { appId: 'dancefactory', version: '1.1.6', versionCode: 6, artifacts };

function makeStore({ failArtifact = false, changedHead = false, initialPayload = oldPayload } = {}) {
  const calls = [];
  let head = signManifest(initialPayload, testPrivateKey, trustedKeys);
  let reads = 0;
  return {
    calls,
    async readManifest() {
      reads++;
      if (changedHead && reads === 2) return Buffer.from('线上清单已变化');
      return head;
    },
    async checkArtifact(artifact, allowMissing) {
      calls.push(`check:${artifact.fileName}:${allowMissing}`);
      if (failArtifact && !allowMissing) throw new Error('下载校验失败');
      return !allowMissing;
    },
    async put(key) {
      calls.push(`put:${key}`);
      if (key === 'dancefactory/updates/latest.json') head = signManifest(newPayload, testPrivateKey, trustedKeys);
    },
  };
}

const plan = { payload: newPayload, paths: { windows: 'windows.zip', android: 'android.apk' },
  manifestPath: 'latest.json', trustedKeys };

test('两端信任相同的当前公钥，并接受新旧两份真实签名样本', () => {
  const keys = readTrustedKeys();
  for (const file of ['update-manifest.json', 'update-manifest-current.json']) {
    assert.equal(verifyManifest(readFileSync(new URL(`../src/test/resources/${file}`, import.meta.url)), keys).appId, 'dancefactory');
  }
});

test('不匹配的私钥不能生成发布清单', () => {
  assert.throws(() => signManifest(newPayload, testPrivateKey, readTrustedKeys()), /不匹配/);
});

test('修改载荷而不重签名会被拒绝', () => {
  const envelope = JSON.parse(signManifest(newPayload, testPrivateKey, trustedKeys));
  envelope.payload = Buffer.from(JSON.stringify({ ...newPayload, version: '9.9.9' })).toString('base64');
  assert.throws(() => verifyManifest(Buffer.from(JSON.stringify(envelope)), trustedKeys), /签名验证失败/);
});

test('两端安装包校验完成后才发布 latest.json', async () => {
  const store = makeStore();
  await publishRelease(plan, store);
  const latestIndex = store.calls.indexOf('put:dancefactory/updates/latest.json');
  assert.ok(latestIndex > store.calls.indexOf('check:windows.zip:false'));
  assert.ok(latestIndex > store.calls.indexOf('check:android.apk:false'));
  assert.equal(latestIndex, store.calls.length - 1);
});

test('任意安装包校验失败都不会切换线上版本', async () => {
  const store = makeStore({ failArtifact: true });
  await assert.rejects(publishRelease(plan, store), /下载校验失败/);
  assert.ok(!store.calls.includes('put:dancefactory/updates/latest.json'));
});

test('线上版本在发布期间变化时停止切换', async () => {
  const store = makeStore({ changedHead: true });
  await assert.rejects(publishRelease(plan, store), /发生变化/);
  assert.ok(!store.calls.includes('put:dancefactory/updates/latest.json'));
});

test('拒绝发布低版本或回退 versionCode', async () => {
  const store = makeStore();
  await assert.rejects(publishRelease({ ...plan, payload: { ...newPayload, version: '1.1.4' } }, store), /禁止回退/);
  await assert.rejects(publishRelease({ ...plan, payload: { ...newPayload, versionCode: 4 } }, store), /禁止回退/);
  assert.equal(store.calls.length, 0);
  assert.equal(compareVersions('1.1.10', '1.1.6'), 1);
  assert.equal(compareVersions('1.1.6.1', '1.1.6'), 1);
  assert.equal(compareVersions('1.1.6', '1.1.6.0'), 0);
  assert.equal(compareVersions('1.1.6.2', '1.1.6.10'), -1);
});

test('同版本修正必须显式开启并使用新的文件地址', async () => {
  const previous = { ...newPayload, artifacts: { ...artifacts, windows: { ...artifacts.windows,
    fileName: 'old-windows.zip', downloadUrl: 'https://example.com/old-windows.zip', sha256: 'c'.repeat(64) } } };
  const blockedStore = makeStore({ initialPayload: previous });
  await assert.rejects(publishRelease(plan, blockedStore), /相同版本已发布不同内容/);
  assert.equal(blockedStore.calls.length, 0);
  const store = makeStore({ initialPayload: previous });
  await publishRelease({ ...plan, replaceManifest: true }, store);
  assert.ok(store.calls.includes('put:dancefactory/updates/latest.json'));
  const cachedPrevious = { ...newPayload, artifacts: { ...artifacts, windows: { ...artifacts.windows, sha256: 'c'.repeat(64) } } };
  const cachedStore = makeStore({ initialPayload: cachedPrevious });
  await assert.rejects(publishRelease({ ...plan, replaceManifest: true }, cachedStore), /必须使用新的下载地址/);
  assert.equal(cachedStore.calls.length, 0);
});
