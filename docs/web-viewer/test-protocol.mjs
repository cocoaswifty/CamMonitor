// Run with: node --test docs/web-viewer/test-protocol.mjs (Node.js >= 20)
import test from "node:test";
import assert from "node:assert/strict";
import {
  seal, openEnvelope, otpKey, ecdhKey, toBase64, concat
} from "./protocol.js";

async function makeIdentity(name) {
  const kp = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]
  );
  const pkcs8 = await crypto.subtle.exportKey("pkcs8", kp.privateKey);
  const spki = await crypto.subtle.exportKey("spki", kp.publicKey);
  return {
    deviceId: name,
    publicKeyBase64: toBase64(spki),
    privateEcdh: await crypto.subtle.importKey(
      "pkcs8", pkcs8, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]
    ),
    privateEcdsa: await crypto.subtle.importKey(
      "pkcs8", pkcs8, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]
    )
  };
}

test("Browser P-256 ECDH key agreement derives the same AES-256 key", async () => {
  const a = await makeIdentity("alpha");
  const b = await makeIdentity("beta");
  const ak = await ecdhKey(a, b.publicKeyBase64);
  const bk = await ecdhKey(b, a.publicKeyBase64);
  assert.deepEqual(ak, bk);
  assert.equal(ak.length, 32);
});

test("OTP pairing key is deterministic and 32 bytes", async () => {
  const sample = "Q4PxazUxt1KcnIA4xFjYSPNbJkk5xCOexC08jpWRk64=";
  const first = await otpKey(sample);
  const second = await otpKey(sample);
  assert.deepEqual(first, second);
  assert.equal(first.length, 32);
});

test("Signed encrypted signaling roundtrip validates identity and rejects replay", async () => {
  const a = await makeIdentity("cam");
  const b = await makeIdentity("browser");
  const key = await ecdhKey(a, b.publicKeyBase64);
  const msg = { type: "ANSWER", sdp: "v=0\\r\\n" };
  const env = await seal(a, b.deviceId, msg, key);
  const opened = await openEnvelope(
    env, b.deviceId, a.deviceId, key, a.publicKeyBase64
  );
  assert.deepEqual(opened, msg);
  assert.equal(await openEnvelope(
    env, b.deviceId, a.deviceId, key, a.publicKeyBase64
  ), null);
});

test("Signed envelope does not decrypt for an incorrect recipient", async () => {
  const a = await makeIdentity("cam2");
  const b = await makeIdentity("browser2");
  const key = await ecdhKey(a, b.publicKeyBase64);
  const env = await seal(a, b.deviceId, { type: "OFFER", sdp: "v=0" }, key);
  assert.equal(await openEnvelope(
    env, "some-other-viewer", a.deviceId, key, a.publicKeyBase64
  ), null);
});

test("Tampered ciphertext cannot pass ECDSA envelope signature", async () => {
  const a = await makeIdentity("cam3");
  const b = await makeIdentity("browser3");
  const key = await ecdhKey(a, b.publicKeyBase64);
  const env = await seal(a, b.deviceId, { type: "OFFER", sdp: "v=0" }, key);
  const corrupt = { ...env, ciphertext: env.ciphertext.slice(0, -2) + "AA" };
  assert.equal(await openEnvelope(
    corrupt, b.deviceId, a.deviceId, key, a.publicKeyBase64
  ), null);
});
