package com.tku.refchecker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Paper {
    
    private String rawInput;     // 使用者輸入的原始名稱（或從整串文獻、PDF 中清洗出來的原始標題）
    
    private String foundTitle;   // Gemini 搜尋驗證後抓取到的「官方完整全稱」
    
    private String jumpUrl;      // 升級版直達連結（有找到就直接跳轉 IEEE/ACM/arXiv 官方頁面；找不到則退回 Google 搜尋）
    
    /**
     * 🎯 2.0 版多網域判斷狀態：
     * - MATCHED_IEEE     : 確有收錄且成功提取 IEEE 直達網址
     * - MATCHED_ACM      : 確有收錄且成功提取 ACM 直達網址
     * - MATCHED_ARXIV    : 確有收錄且成功提取 arXiv 直達網址
     * - MATCHED_SPRINGER : 確有收錄且成功提取 Springer 直達網址
     * - MATCHED_ELSEVIER : 確有收錄且成功提取 ScienceDirect 直達網址
     * - MATCHED_OTHER    : 有此論文，但收錄於其他非主流平台（走全網核對）
     * - NOT_FOUND        : ⚠️ 系統查無此文（前端將自動觸發置頂與紅色高亮警告）
     * - ERROR            : ⚠️ 系統連線異常或解析失敗（前端自動置頂）
     */
    private String status;       
}