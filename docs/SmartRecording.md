# Smart Event Recording / 智慧事件錄影（MVP）

This optional **on-device** mode uses CameraX ImageAnalysis and the ML Kit bundled
image-labeling model. It is independent of the existing WebRTC live camera mode.

## 行為

1. 使用者從首頁的攝影機圖示進入「智慧事件錄影」，明確點選啟動。
2. 啟動 Camera 前景服務，開啟後鏡頭並在約 **4 秒**的視窗內分析畫面。
3. 未偵測到人物／動物：釋放 CameraX 相機，**等待 10 秒後**重新開啟檢查。
4. 發現目標：啟動無聲 MP4 錄影，保持相機開啟並持續辨識。
5. 連續 **4 秒**沒有再次辨識到目標後，完成錄影並釋放相機，再進入 10 秒等待週期。
6. 停止按鈕或前景服務通知中的停止操作會取消掃描並結束錄影。

影片保存在裝置專屬的 `Movies/CamMonitorEvents` 目錄，可在 App 內播放或透過
Android 分享面板匯出。沒有自動上傳。

## 安全與使用限制

- 模型透過 `com.google.mlkit:image-labeling:17.0.9` 隨 App 安裝，**不需網路推論**。
- **圖片標籤不是多物件偵測**；少見動物、遠處的人物、暗處與遮擋可能漏判。
- 同時使用 `ImageAnalysis + VideoCapture` 並非所有裝置都支援；失敗會釋放鏡頭並等待下一次嘗試。
- 與現有 WebRTC 攝影機模式互斥，避免兩個模組搶用相機。
- Android 的相機前景服務必須由前景 App 啟動，不支援 App 尚未執行就靜默開啟相機。
- 不含動態偵測通知、影片自動刪除、容量配額、音訊錄製或預錄緩衝。請定期管理儲存空間。
- 目前**尚未進行完整 Android Gradle 編譯或實機相容性驗證**，不建議直接部署為無人值守的安全警報系統。

## 驗證

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

實機測試請涵蓋：空畫面→相機關閉→10 秒後再掃描；人物及貓狗進入畫面；
辨識到目標後開始錄影、離開超過 4 秒停止；背景運作；主動停止時檔案完成；
低光源、旋轉裝置、儲存空間不足和 CameraX 不支援的硬體。

## 相關程式

- `service/SmartRecordingPolicy.kt`: 偵測類別與週期策略
- `service/SmartRecordingService.kt`: CameraX、ML Kit、事件錄影與前景服務
- `ui/smart/SmartRecordingScreen.kt`: 開始／停止與影片清單
- `service/SmartRecordingPolicyTest.kt`: 策略單元測試
