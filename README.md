## 🚧 專案開發狀態 (Current Status)

本專案目前仍處於 **開發階段 (Work in Progress)**，主要核心邏輯已完成，但仍有以下部分正在優化中：

### 🛠️ 正在處理的挑戰
- **非結構化資料處理 (Unstructured Data Processing)**：目前系統對於「非標準格式」的引用字串（如：缺漏作者或年份的參考文獻）解析精準度尚待提升。我們正在開發更強健的字串清洗與正規化邏輯。
- **Prompt Engineering 優化**：
  - 正在微調 Gemini API 的 Prompt 結構，以減少 AI 在邊際情況下的「幻覺 (Hallucination)」。
  - 優化 JSON 回傳格式的穩定性，確保在各種複雜文獻標題下皆能正確解析。

### 📅 下一步規劃 (Roadmap)
- [ ] 導入 Regex 預處理層，先過濾非結構化字串。
- [ ] 增加多模型對比（如同時對比 Gemini 1.5 Pro 與 Flash 的判斷結果）。
- [ ] 支援批次上傳 .txt 或 .bib 檔案進行大規模校核。