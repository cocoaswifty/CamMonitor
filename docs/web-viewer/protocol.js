// CamMonitor browser transport. No third-party JavaScript or CDN runtime.
const enc = new TextEncoder();
const dec = new TextDecoder();
export const utf8 = (text) => enc.encode(text);
export const str = (data) => dec.decode(data);

export function concat(...buffers) {
  const arrays = buffers.map(v => v instanceof Uint8Array ? v : new Uint8Array(v));
  const result = new Uint8Array(arrays.reduce((total, part) => total + part.length, 0));
  let offset = 0;
  for (const part of arrays) { result.set(part, offset); offset += part.length; }
  return result;
}

export function toBase64(value) {
  const data = value instanceof Uint8Array ? value : new Uint8Array(value);
  let binary = "";
  for (let start = 0; start < data.length; start += 4096) {
    binary += String.fromCharCode(...data.subarray(start, start + 4096));
  }
  return btoa(binary);
}

export function fromBase64(value) {
  const binary = atob(value);
  return Uint8Array.from(binary, char => char.charCodeAt(0));
}

function toHex(value) {
  return Array.from(value, byte => byte.toString(16).padStart(2, "0")).join("");
}

function randomBytes(count) {
  return crypto.getRandomValues(new Uint8Array(count));
}

function uuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const bytes = randomBytes(16);
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const id = toHex(bytes);
  return id.slice(0, 8) + "-" + id.slice(8, 12) + "-" + id.slice(12, 16) +
    "-" + id.slice(16, 20) + "-" + id.slice(20);
}

function request(store, mode, fn) {
  return new Promise((resolve, reject) => {
    const tx = store.transaction("kv", mode);
    const op = fn(tx.objectStore("kv"));
    op.onsuccess = () => resolve(op.result);
    op.onerror = () => reject(op.error);
    tx.onerror = () => reject(tx.error);
  });
}

async function openDb() {
  return new Promise((resolve, reject) => {
    const call = indexedDB.open("cammonitor-web-keys", 1);
    call.onupgradeneeded = () => { call.result.createObjectStore("kv"); };
    call.onerror = () => reject(call.error);
    call.onsuccess = () => resolve(call.result);
  });
}

export async function getSaved(key) {
  const db = await openDb();
  try { return await request(db, "readonly", store => store.get(key)); }
  finally { db.close(); }
}

export async function save(key, value) {
  const db = await openDb();
  try { await request(db, "readwrite", store => store.put(value, key)); }
  finally { db.close(); }
}

export async function remove(key) {
  const db = await openDb();
  try { await request(db, "readwrite", store => store.delete(key)); }
  finally { db.close(); }
}

export async function identity() {
  const stored = await getSaved("identity");
  if (stored?.privateEcdh && stored?.privateEcdsa && stored?.publicKeyBase64) return stored;

  // Android uses a single P-256 key for ECDSA signing and ECDH agreement.
  // WebCrypto uses separate algorithm-specific handles; import the same PKCS8
  // private key into both algorithms, non-extractable, then persist CryptoKeys.
  const seed = await crypto.subtle.generateKey(
    { name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]
  );
  const pkcs8 = await crypto.subtle.exportKey("pkcs8", seed.privateKey);
  const spki = await crypto.subtle.exportKey("spki", seed.publicKey);
  const privateEcdh = await crypto.subtle.importKey(
    "pkcs8", pkcs8, { name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]
  );
  const privateEcdsa = await crypto.subtle.importKey(
    "pkcs8", pkcs8, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]
  );
  const result = { deviceId: uuid(), privateEcdh, privateEcdsa,
    publicKeyBase64: toBase64(spki) };
  await save("identity", result);
  return result;
}

// Existing Android HKDF empty-salt compatibility: one HMAC-SHA256 block of
// info || 0x01 keyed by the original secret (NOT RFC 5869 empty-salt HKDF).
async function androidKdf(secret, info) {
  const macKey = await crypto.subtle.importKey(
    "raw", secret, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  return new Uint8Array(await crypto.subtle.sign(
    "HMAC", macKey, concat(utf8(info), new Uint8Array([1]))
  ));
}

export async function otpKey(otp) {
  // Android uses UTF-8 of the *base64 string*, not decoded OTP bytes.
  return androidKdf(utf8(otp), "CamMonitor-OTP");
}

export async function ecdhKey(local, peerPublicKeyBase64) {
  const peer = await crypto.subtle.importKey(
    "spki", fromBase64(peerPublicKeyBase64),
    { name: "ECDH", namedCurve: "P-256" }, false, []
  );
  const shared = await crypto.subtle.deriveBits(
    { name: "ECDH", public: peer }, local.privateEcdh, 256
  );
  return androidKdf(new Uint8Array(shared), "CamMonitor-E2EE");
}

function derInt(value) {
  let start = 0;
  while (start < value.length - 1 && value[start] === 0) start++;
  let body = value.subarray(start);
  if ((body[0] & 0x80) !== 0) body = concat([0], body);
  return concat([2, body.length], body);
}

// WebCrypto's P-256 ECDSA signature is IEEE P1363 r||s;
// Android's SHA256withECDSA expects ASN.1 DER.
function rawToDer(raw) {
  if (raw.length !== 64) throw new Error("Invalid ECDSA signature length");
  const r = derInt(raw.subarray(0, 32));
  const s = derInt(raw.subarray(32, 64));
  return concat([0x30, r.length + s.length], r, s);
}

function derToRaw(der) {
  if (der.length < 8 || der[0] !== 0x30 || der[1] !== der.length - 2) {
    throw new Error("Invalid ECDSA DER sequence");
  }
  let offset = 2;
  function readInt() {
    if (der[offset++] !== 2) throw new Error("Invalid ECDSA integer");
    const length = der[offset++];
    if (length < 1 || offset + length > der.length) throw new Error("Invalid ECDSA length");
    let chunk = der.subarray(offset, offset + length);
    offset += length;
    while (chunk.length > 1 && chunk[0] === 0) chunk = chunk.subarray(1);
    if (chunk.length > 32) throw new Error("ECDSA integer too large");
    const output = new Uint8Array(32);
    output.set(chunk, 32 - chunk.length);
    return output;
  }
  const r = readInt();
  const s = readInt();
  if (offset !== der.length) throw new Error("ECDSA DER trailing bytes");
  return concat(r, s);
}

async function sign(local, payload) {
  const raw = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" }, local.privateEcdsa, utf8(payload)
  );
  return toBase64(rawToDer(new Uint8Array(raw)));
}

async function verify(payload, encodedSignature, peerPublicKeyBase64) {
  const key = await crypto.subtle.importKey(
    "spki", fromBase64(peerPublicKeyBase64),
    { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]
  );
  return crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" }, key,
    derToRaw(fromBase64(encodedSignature)), utf8(payload)
  );
}

export async function seal(sender, recipientId, message, bytesKey) {
  const timestamp = Date.now();
  const nonce = toHex(randomBytes(16));
  const iv = randomBytes(12);
  const aes = await crypto.subtle.importKey(
    "raw", bytesKey, { name: "AES-GCM" }, false, ["encrypt"]
  );
  const combined = new Uint8Array(await crypto.subtle.encrypt(
    { name: "AES-GCM", iv, tagLength: 128 }, aes, utf8(JSON.stringify(message))
  ));
  const ciphertext = toBase64(combined.subarray(0, combined.length - 16));
  const tag = toBase64(combined.subarray(combined.length - 16));
  const payload = sender.deviceId + recipientId + timestamp + nonce + ciphertext;
  const signature = await sign(sender, payload);
  return { senderId: sender.deviceId, recipientId, timestamp, nonce,
    ciphertext, iv: toBase64(iv), tag, signature };
}

const seen = new Map();
export async function openEnvelope(envelope, receiverId, expectedSenderId, key, publicKey) {
  if (envelope.recipientId !== receiverId || envelope.senderId !== expectedSenderId) return null;
  const now = Date.now();
  if (!Number.isSafeInteger(envelope.timestamp) ||
      Math.abs(now - envelope.timestamp) > 30_000 ||
      typeof envelope.nonce !== "string" || !/^[0-9a-f]{32}$/.test(envelope.nonce)) return null;
  if (seen.has(envelope.nonce)) return null;

  const payload = envelope.senderId + envelope.recipientId + envelope.timestamp +
    envelope.nonce + envelope.ciphertext;
  // Pairing confirmation authenticates through OTP and is signed *after*
  // decryption against the camera public key from the decrypted content.
  if (publicKey && !await verify(payload, envelope.signature, publicKey)) return null;
  const aes = await crypto.subtle.importKey(
    "raw", key, { name: "AES-GCM" }, false, ["decrypt"]
  );
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromBase64(envelope.iv), tagLength: 128 },
    aes, concat(fromBase64(envelope.ciphertext), fromBase64(envelope.tag))
  );
  const value = JSON.parse(str(plain));
  if (!publicKey) {
    if (value.type !== "PAIR_CONFIRM" || value.paDeviceId !== expectedSenderId) return null;
    if (!await verify(payload, envelope.signature, value.paPublicKeyBase64)) return null;
  }
  seen.set(envelope.nonce, now);
  for (const [key, value] of seen) { if (now - value > 60_000) seen.delete(key); }
  if (seen.size > 256) seen.delete(seen.keys().next().value);
  return value;
}

const twoBytes = num => new Uint8Array([(num >> 8) & 255, num & 255]);
const stringBytes = text => { const b = utf8(text); return concat(twoBytes(b.length), b); };
function remainingLength(len) {
  const out = [];
  do { let digit = len % 128; len = Math.floor(len / 128);
    if (len > 0) digit |= 128; out.push(digit);
  } while (len > 0);
  return new Uint8Array(out);
}

/** Minimal MQTT 3.1.1 browser WebSocket client (QoS0 publish, QoS1 subscribe). */
export class MqttSignal {
  constructor(deviceId, onMessage, onDrop) {
    this.deviceId = deviceId;
    this.onMessage = onMessage;
    this.onDrop = onDrop;
    this.ws = null;
    this.ready = false;
    this.timer = null;
    this.connectPending = null;
  }

  packet(type, body = new Uint8Array()) {
    if (this.ws?.readyState !== WebSocket.OPEN) return false;
    this.ws.send(concat([type], remainingLength(body.length), body));
    return true;
  }

  async connect() {
    this.close();
    this.ready = false;
    const clientId = "cammonitor-web-" + toHex(randomBytes(8));
    const ws = new WebSocket("wss://broker.hivemq.com:8884/mqtt", ["mqtt"]);
    this.ws = ws;
    ws.binaryType = "arraybuffer";
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => fail(new Error("MQTT connection timed out")), 12000);
      let completed = false;
      const fail = error => {
        if (completed) return;
        completed = true; clearTimeout(timeout);
        ws.close(); reject(error);
      };
      ws.onopen = () => {
        const header = concat(
          stringBytes("MQTT"), [4, 2], twoBytes(45), stringBytes(clientId)
        );
        this.packet(0x10, header);
      };
      ws.onerror = () => {};
      ws.onclose = () => {
        const wasReady = this.ready;
        this.ready = false;
        if (this.timer) clearInterval(this.timer);
        if (!completed) fail(new Error("MQTT WebSocket disconnected before subscribing"));
        else if (wasReady) this.onDrop();
      };
      ws.onmessage = event => {
        try {
          const bytes = new Uint8Array(event.data);
          let cursor = 0;
          while (cursor < bytes.length) {
            const first = bytes[cursor++];
            let multiplier = 1, remaining = 0, digit, count = 0;
            do {
              if (cursor >= bytes.length || ++count > 4) throw Error("Invalid MQTT length");
              digit = bytes[cursor++];
              remaining += (digit & 127) * multiplier;
              multiplier *= 128;
            } while ((digit & 128) !== 0);
            if (cursor + remaining > bytes.length) throw Error("Truncated MQTT packet");
            const body = bytes.subarray(cursor, cursor + remaining);
            cursor += remaining;
            switch (first >> 4) {
              case 2: // CONNACK
                if (body.length < 2 || body[1] !== 0) {
                  fail(Error("MQTT broker rejected connection"));
                  break;
                }
                this.packet(0x82, concat(
                  twoBytes(1), stringBytes("cammonitor/" + this.deviceId + "/rx"), [1]
                ));
                break;
              case 9: // SUBACK
                if (body.length < 3 || body[2] === 128) {
                  fail(Error("MQTT broker refused subscription"));
                  break;
                }
                if (!completed) {
                  completed = true; clearTimeout(timeout); this.ready = true;
                  this.timer = setInterval(() => this.packet(0xC0), 20000);
                  resolve();
                }
                break;
              case 3: { // PUBLISH (handle either QoS0 or QoS1)
                if (body.length < 2) break;
                const topicLength = body[0] * 256 + body[1];
                let offset = 2 + topicLength;
                if (offset > body.length) break;
                const qos = (first >> 1) & 3;
                if (qos === 1) {
                  if (offset + 2 > body.length) break;
                  this.packet(0x40, body.subarray(offset, offset + 2));
                  offset += 2;
                }
                try {
                  const message = JSON.parse(str(body.subarray(offset)));
                  Promise.resolve(this.onMessage(message)).catch(console.warn);
                } catch (error) { console.warn("Invalid MQTT message", error); }
                break;
              }
            }
          }
        } catch (error) { console.warn("MQTT decode failure", error); fail(error); }
      };
    });
  }

  publish(recipientId, envelope) {
    if (!this.ready) throw Error("MQTT is disconnected");
    const topic = "cammonitor/" + recipientId + "/rx";
    const json = utf8(JSON.stringify(envelope));
    if (!this.packet(0x30, concat(stringBytes(topic), json))) {
      throw Error("Failed to publish MQTT message");
    }
  }

  close() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null; this.ready = false;
    if (this.ws) { try { this.ws.close(); } catch (_) {} }
    this.ws = null;
  }
}
