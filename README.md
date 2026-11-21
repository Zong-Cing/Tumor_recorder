# 腫瘤個管通話助理 (TumorRecorder)

專為**腫瘤個案管理師（Oncology Case Manager）**設計的 Android 通話錄音、AI 逐字稿與摘要自動寄信 App（Kotlin + Jetpack Compose）。

## 核心功能

1. **無障礙通話錄音服務（`AccessibilityService`）＋ 通話監聽**
   - **無障礙服務搭配 `VOICE_RECOGNITION` 音源**：自用側載模式（不上架 Play 商店），於手機「設定 → 無障礙」啟用 `CallRecorderAccessibilityService`（`canRetrieveWindowContent="false"`，不讀取螢幕畫面），即可在 Android 10+ 通話中併發使用 `VOICE_RECOGNITION` 音源錄製 44.1kHz / 96kbps `.m4a` 音檔，避免被系統強制靜音。
   - **音源彈性切換**：設定頁支援切換 `VOICE_RECOGNITION`（無障礙推薦）／`VOICE_COMMUNICATION`／`MIC`／`UNPROCESSED`，並保留通知列一鍵擴音、手動錄音與匯入音檔作為備案。
   - **來去電自動偵測**：透過 `CallStateReceiver` 監聽接通／掛斷，自動啟動前景錄音並於通話結束後排程背景 AI 分析。

2. **Google Gemini AI 腫瘤個管結構化摘要 ＋ 完整逐字稿**
   - 直接透過 **Gemini Files API** 上傳音檔（預設使用最新的 **`gemini-4-hydrogen`**，可於設定頁切換 `gemini-4-carbon` / `gemini-4-neon` / `gemini-3.8-flash` / `gemini-3.5-flash-lite`），以嚴格 JSON Schema 一次擷取：
     - **病人姓名**（發音不確定自動標註「請核對」）
     - **病歷號碼或身分證字號**（自動判別類型）
     - **所屬個管師姓名**
     - **來電者身分**（病人本人／家屬／院內同仁）
     - **諮詢分類**（副作用處理、用藥諮詢、排程、緊急症狀、行政文件等）
     - **緊急程度**（一般／需追蹤／緊急，偵測化療發燒等警訊自動標示「緊急」）
     - **通話討論重點摘要**與**後續待辦／追蹤事項**
     - **完整逐字稿**（標記個管師與來電者）
   - 摘要完成後自動刪除上傳至 Gemini 暫存空間的音檔，降低敏感醫療語音留存風險。
   - 可在紀錄詳情頁**人工核對並編輯修正**病人姓名、病歷號、個管師與摘要後一鍵重寄。

3. **Gmail SMTP 背景自動寄信**
   - 摘要完成後自動於背景寄出排版清晰的 HTML + 純文字信件（支援多位收件人、可選是否附加錄音檔、可選寄出成功後自動刪除本機音檔）。
   - 所有 API Key 與 Gmail 應用程式密碼皆以 Android Keystore (`EncryptedSharedPreferences`) 加密儲存。

## 專案架構

```text
app/src/main/java/com/tumorrecorder/app/
├── TumorRecorderApp.kt            # Application 進入點與 DI 容器
├── MainActivity.kt                # 權限申請、無障礙狀態偵測與通知 Deep-link
├── domain/
│   ├── PromptBuilder.kt           # 腫瘤個管專用繁體中文 Prompt
│   └── model/                     # OncologySummaryResult, AppSettings, RecordingAudioSource
├── data/
│   ├── local/                     # Room DB (CallRecordEntity, DAO) & Encrypted Settings
│   ├── remote/                    # GeminiService (Files API + JSON Schema), EmailService (SMTP)
│   └── repository/                # CallRecordRepository (匯入音檔與 WorkManager 排程)
├── service/
│   ├── CallRecorderAccessibilityService.kt # 最小權限無障礙服務（授權通話中 VOICE_RECOGNITION 併發收音）
│   ├── CallStateReceiver.kt       # 監聽來去電狀態 (PHONE_STATE)
│   ├── CallRecordingService.kt    # 前景錄音服務 + 音源切換 + 擴音控制
│   └── CallProcessingWorker.kt    # 背景 AI 轉錄摘要 -> 存檔 -> 自動寄信
└── ui/
    ├── home/                      # 首頁：無障礙引導、快速撥號、錄音控制、匯入、搜尋與紀錄列表
    ├── detail/                    # 詳情頁：音檔播放、個管欄位核對編輯、摘要/逐字稿頁籤、重寄
    └── settings/                  # 設定頁：無障礙與音源設定、Gemini 連線測試、Gmail SMTP 測試
```

## 建置與安裝

### 環境需求
- Android Studio Ladybug / Meerkat 或以上版本
- JDK 17+（本專案已驗證於 JDK 21）
- Android SDK API 35（支援 Android 8.0 / API 26 以上手機）

### 指令建置 APK 與執行單元測試
```bash
# 執行單元測試
./gradlew testDebugUnitTest

# 編譯 Debug APK
./gradlew assembleDebug
```
編譯完成的 APK 位於 `app/build/outputs/apk/debug/app-debug.apk`，可直接安裝至 Android 手機測試。

## 首次使用設定步驟

1. **授予權限**：開啟 App 後點擊「立即授權」，允許麥克風、電話狀態、通話紀錄與通知權限。
2. **啟用無障礙通話錄音服務（重要）**：
   - Android 13 / 14 / 15 自行安裝 APK 時，請先點首頁或設定頁的 **「解除受限制設定」** → 點右上角「︙」→ 選擇 **「允許受限制的設定」**。
   - 再點 **「前往無障礙設定」** →「已安裝的應用程式」→ 開啟 **「腫瘤個管通話助理」**。
   - 預設音源為 `VOICE_RECOGNITION`；若特定機型測試後對方聲音仍小，可在設定頁改試 `VOICE_COMMUNICATION`、`UNPROCESSED`，或開啟「通話接通時自動開啟擴音」。
3. **設定 Gemini API Key**：
   - 前往 [Google AI Studio](https://aistudio.google.com/apikey) 建立 API Key。
   - 開啟 App「設定」→ 貼上 Gemini API Key → 點擊「測試 Gemini API 連線」。
4. **設定 Gmail SMTP 自動寄信**：
   - 登入 Google 帳戶 →「安全性」→ 開啟「兩步驟驗證」。
   - 搜尋「應用程式密碼」(App passwords) → 建立一組 16 碼專屬密碼。
   - 在 App「設定」填入寄件者 Gmail、16 碼應用程式密碼、以及收件人 Email → 點擊「寄送測試信件」確認成功。

## 醫療隱私與注意事項

- **最小權限設計**：`CallRecorderAccessibilityService` 設定 `canRetrieveWindowContent="false"`，僅作為 Android 通話音訊併發授權用途，絕不讀取螢幕畫面內容。
- **個資保護與臨床核對**：本 App 處理之資料包含病人姓名、病歷號碼／身分證字號等敏感醫療個資，使用前請確認符合所屬醫療機構之資安政策，且 AI 產生之逐字稿與摘要僅供輔助紀錄，歸檔前務必由個管師人工核對確認。
