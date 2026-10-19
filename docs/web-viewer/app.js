import {
  utf8, str, concat, fromBase64, MqttSignal, identity as loadIdentity,
  save, remove, getSaved, otpKey, ecdhKey, seal, openEnvelope
} from "./protocol.js";

const el = id => document.getElementById(id);
const ui = {
  camera: el("camera"), status: el("status"), pair: el("pair"),
  connect: el("connect"), disconnect: el("disconnect"), forget: el("forget"),
  video: el("video"), sound: el("sound"), fullscreen: el("fullscreen"),
  details: el("details")
};
let local = null;
let paired = null;
let invite = null;
let client = null;
let peer = null;
let keyBytes = null;
let retryOffer = null;
let pendingAnswer = false;
let pendingCandidates = [];
let pendingPairResolve = null;
let pendingPairReject = null;
let pairInProgress = false;
let lastOffer = null;
let controls = null;

function status(text) { ui.status.textContent = text; }

function render() {
  ui.pair.disabled = !invite || pairInProgress;
  ui.connect.disabled = !paired || pairInProgress;
  ui.forget.disabled = !paired || pairInProgress;
  ui.disconnect.disabled = !peer;
  ui.sound.disabled = !peer;
  ui.fullscreen.disabled = !peer;
  ui.camera.textContent = paired ?
    "已配對攝影機：" + paired.name + "。請先在 Android 開啟「攝影機模式」，再按觀看。" :
    invite ?
    "收到 " + invite.name + " 的限時配對邀請，請確保 Android 正顯示網頁配對畫面。" :
    "尚未配對。請從 Android CamMonitor 產生「網頁觀看端配對」連結或 QR Code。";
}

function parseInvitation() {
  const hash = window.location.hash;
  if (!hash.startsWith("#pair=")) return null;
  // Remove the secret-bearing fragment promptly; URL fragments do not reach
  // the GitHub Pages server, but browser copies/history must still be treated carefully.
  history.replaceState(null, "", window.location.pathname + window.location.search);
  const encoded = hash.slice(6).replace(/-/g, "+").replace(/_/g, "/");
  const padded = encoded + "=".repeat((4 - encoded.length % 4) % 4);
  const bytes = fromBase64(padded);
  const json = JSON.parse(str(bytes));
  if (typeof json.deviceId !== "string" ||
      !/^[0-9a-f-]{36}$/i.test(json.deviceId) ||
      typeof json.secret !== "string" ||
      !/^[A-Za-z0-9+/]{43}=$/.test(json.secret) ||
      typeof json.name !== "string") {
    throw new Error("配對連結資料不正確");
  }
  if (json.turn) {
    const turn = json.turn;
    if (typeof turn.urls !== "string" ||
      !(turn.urls.startsWith("turn:") || turn.urls.startsWith("turns:")) ||
      typeof turn.username !== "string" || typeof turn.credential !== "string") {
      throw new Error("配對連結內的 TURN 設定格式不正確");
    }
  }
  return json;
}

function clientDrop() {
  status("訊令連線已中斷。請按「觀看即時影像」重新連線。");
  closePeer();
}

async function mqttReady() {
  if (client?.ready) return client;
  if (client) client.close();
  client = new MqttSignal(local.deviceId, receiveSignal, clientDrop);
  status("正在透過 WSS 連線 MQTT 訊令伺服器…");
  await client.connect();
  return client;
}

async function transmit(message, key, recipientId) {
  if (!client?.ready) throw new Error("MQTT 未連線");
  const packet = await seal(local, recipientId, message, key);
  client.publish(recipientId, packet);
}

async function pairBrowser() {
  if (!invite || pairInProgress) return;
  pairInProgress = true; render();
  try {
    await mqttReady();
    const secret = invite.secret;
    const cameraId = invite.deviceId;
    const otp = await otpKey(secret);
    status("已連線。等待 Android 確認此瀏覽器的配對請求…");
    const confirmation = new Promise((resolve, reject) => {
      pendingPairResolve = resolve; pendingPairReject = reject;
    });

    // One-use, time-limited OTP. Repeat while the camera is still on the
    // pairing screen to tolerate the first MQTT delivery being lost.
    const request = {
      type: "PAIR_REQUEST",
      pbDeviceId: local.deviceId,
      pbName: "Web Viewer",
      pbPublicKeyBase64: local.publicKeyBase64
    };
    const deadline = Date.now() + 23000;
    let completed = false;
    confirmation.then(() => { completed = true; }).catch(() => {});
    while (!completed && Date.now() < deadline) {
      await transmit(request, otp, cameraId);
      await Promise.race([
        confirmation.catch(() => null),
        new Promise(resolve => setTimeout(resolve, 2000))
      ]);
    }
    if (!completed) throw new Error("配對逾時。請重新產生 Android 配對連結。");
    await confirmation;
    status("配對成功。請確認 Android 已進入攝影機模式，再開始觀看。");
  } catch (e) {
    status("配對失敗：" + e.message);
  } finally {
    pendingPairResolve = null;
    pendingPairReject = null;
    pairInProgress = false;
    render();
  }
}

async function receiveSignal(envelope) {
  if (!local || !envelope || typeof envelope !== "object") return;
  try {
    if (invite && envelope.senderId === invite.deviceId &&
        envelope.recipientId === local.deviceId && pendingPairResolve) {
      const otp = await otpKey(invite.secret);
      const answer = await openEnvelope(
        envelope, local.deviceId, invite.deviceId, otp, null
      );
      if (answer?.type === "PAIR_CONFIRM" &&
          answer.paDeviceId === invite.deviceId &&
          typeof answer.paPublicKeyBase64 === "string" &&
          answer.paPublicKeyBase64.length < 4096) {
        const pairedCamera = {
          deviceId: answer.paDeviceId,
          name: answer.paName || invite.name,
          publicKeyBase64: answer.paPublicKeyBase64,
          turn: invite.turn || null
        };
        // Validate that the returned public key works for P-256 ECDH.
        await ecdhKey(local, pairedCamera.publicKeyBase64);
        await save("paired", pairedCamera);
        paired = pairedCamera;
        keyBytes = await ecdhKey(local, paired.publicKeyBase64);
        invite = null;
        pendingPairResolve();
        render();
      }
      return;
    }
    if (!paired || envelope.senderId !== paired.deviceId ||
        envelope.recipientId !== local.deviceId) return;
    if (!keyBytes) keyBytes = await ecdhKey(local, paired.publicKeyBase64);
    const message = await openEnvelope(
      envelope, local.deviceId, paired.deviceId, keyBytes, paired.publicKeyBase64
    );
    if (!message) return;
    if (message.type === "ANSWER" && peer && pendingAnswer) {
      pendingAnswer = false;
      if (retryOffer) clearInterval(retryOffer);
      retryOffer = null;
      await peer.setRemoteDescription({ type: "answer", sdp: message.sdp });
      for (const candidate of pendingCandidates.splice(0)) {
        await peer.addIceCandidate(candidate);
      }
      status("攝影機已回應，正在建立 WebRTC 加密影音通道…");
      if (message.cameraName) ui.camera.textContent = "攝影機：" + message.cameraName;
    } else if (message.type === "ICE_CANDIDATE" && peer) {
      const candidate = {
        candidate: message.sdp,
        sdpMid: message.sdpMid,
        sdpMLineIndex: message.sdpMLineIndex
      };
      if (!peer.remoteDescription) pendingCandidates.push(candidate);
      else await peer.addIceCandidate(candidate);
    } else if (message.type === "REJECT") {
      status("攝影機拒絕連線：" + message.reason);
      closePeer();
    }
  } catch (e) {
    console.warn("Verified signaling message was not processed:", e);
  }
}

function closePeer() {
  if (retryOffer) clearInterval(retryOffer);
  retryOffer = null; lastOffer = null; pendingAnswer = false;
  pendingCandidates = []; controls = null;
  if (peer) { peer.close(); peer = null; }
  ui.video.pause(); ui.video.srcObject = null;
  ui.details.textContent = "";
  render();
}

function handleCommand(raw) {
  try {
    const command = JSON.parse(raw);
    if (command.cmd === "BATTERY") {
      ui.details.textContent = "Android 電量 " + command.pct + "%" +
        (command.charging ? " · 充電中" : "");
    } else if (command.cmd === "RENAME") {
      ui.camera.textContent = "攝影機：" + command.name;
    } else if (command.cmd === "FPS_REPORT") {
      ui.details.textContent += " · " + command.fps + " FPS";
    }
  } catch (_) {}
}

function bindControlChannel(channel) {
  channel.onopen = () => {
    try { channel.send(JSON.stringify({ cmd: "QUERY_BATTERY" })); } catch (_) {}
  };
  channel.onmessage = evt => { if (typeof evt.data === "string") handleCommand(evt.data); };
  controls = channel;
}

async function watch() {
  if (!paired || !local) return;
  try {
    closePeer();
    await mqttReady();
    if (!keyBytes) keyBytes = await ecdhKey(local, paired.publicKeyBase64);
    const servers = [
      { urls: "stun:stun.l.google.com:19302" },
      { urls: "stun:stun1.l.google.com:19302" }
    ];
    if (paired.turn) servers.push(paired.turn);
    peer = new RTCPeerConnection({ iceServers: servers });
    const active = peer;
    const stream = new MediaStream();
    ui.video.srcObject = stream;
    ui.video.muted = true;
    ui.sound.textContent = "開啟聲音";

    active.ontrack = evt => {
      const media = ui.video.srcObject;
      if (media && !media.getTracks().some(track => track.id === evt.track.id)) {
        media.addTrack(evt.track);
        ui.video.play().catch(() => {
          status("已收到影像。請點擊播放或開啟聲音。");
        });
      }
    };
    active.ondatachannel = evt => bindControlChannel(evt.channel);
    bindControlChannel(active.createDataChannel("browser-controls"));
    active.onicecandidate = event => {
      if (event.candidate && client?.ready) {
        const c = event.candidate;
        transmit({
          type: "ICE_CANDIDATE",
          sdpMid: c.sdpMid || "",
          sdpMLineIndex: c.sdpMLineIndex || 0,
          sdp: c.candidate
        }, keyBytes, paired.deviceId).catch(console.warn);
      }
    };
    active.onconnectionstatechange = () => {
      if (peer !== active) return;
      switch (active.connectionState) {
        case "connected":
          status("已建立遠端即時影音連線");
          break;
        case "failed":
          status("WebRTC 連線失敗。若兩端使用不同 Wi-Fi 或行動網路，請在 Android 設定 TURN 後重新配對。");
          break;
        case "disconnected":
          status("網路連線中斷，等待 WebRTC 恢復…");
          break;
      }
    };
    // Include application/datachannel and audio/video m-lines in the offer,
    // matching the existing Android CameraMonitorService sender.
    active.addTransceiver("video", { direction: "recvonly" });
    active.addTransceiver("audio", { direction: "recvonly" });
    const offer = await active.createOffer();
    await active.setLocalDescription(offer);
    lastOffer = active.localDescription.sdp;
    pendingAnswer = true;
    status("等待 Android 回應觀看請求…");
    await transmit({ type: "OFFER", sdp: lastOffer }, keyBytes, paired.deviceId);
    retryOffer = setInterval(() => {
      if (!pendingAnswer || !peer || !client?.ready) return;
      transmit({ type: "OFFER", sdp: lastOffer }, keyBytes, paired.deviceId)
        .catch(console.warn);
    }, 5000);
    render();
  } catch (e) {
    closePeer();
    status("無法觀看：" + e.message);
  }
}

ui.pair.onclick = pairBrowser;
ui.connect.onclick = watch;
ui.disconnect.onclick = () => { closePeer(); status("已停止觀看"); };
ui.forget.onclick = async () => {
  closePeer();
  if (client) { client.close(); client = null; }
  await remove("paired");
  paired = null; keyBytes = null;
  status("已從此瀏覽器清除配對資料。若需撤銷攝影機端授權，請於 Android 管理已配對裝置。");
  render();
};
ui.sound.onclick = () => {
  ui.video.muted = !ui.video.muted;
  ui.video.play().catch(() => {});
  ui.sound.textContent = ui.video.muted ? "開啟聲音" : "關閉聲音";
};
ui.fullscreen.onclick = () => {
  if (ui.video.requestFullscreen) ui.video.requestFullscreen().catch(() => {});
  else if (ui.video.webkitEnterFullscreen) ui.video.webkitEnterFullscreen();
};

async function initialize() {
  try {
    if (!window.isSecureContext || !crypto.subtle || !window.RTCPeerConnection) {
      throw Error("需要 HTTPS、安全瀏覽器與 WebRTC");
    }
    invite = parseInvitation();
    local = await loadIdentity();
    paired = await getSaved("paired") || null;
    if (paired) keyBytes = await ecdhKey(local, paired.publicKeyBase64);
    if (invite) {
      status("收到配對邀請。點選「配對此瀏覽器」。");
    } else if (paired) {
      status("已保存配對。Android 啟動攝影機模式後即可觀看。");
    } else {
      status("尚未配對攝影機");
    }
    render();
  } catch (e) {
    status("初始化失敗：" + e.message);
  }
}
initialize();
