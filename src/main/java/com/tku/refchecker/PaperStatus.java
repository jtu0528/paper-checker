package com.tku.refchecker;

/**
 * 🎯 論文查驗狀態（取代原本散落在程式中的狀態字串，打錯字時編譯就會失敗）
 */
public enum PaperStatus {
    MATCHED_IEEE,       // 確有收錄且成功提取 IEEE 直達網址
    MATCHED_ACM,        // 確有收錄且成功提取 ACM 直達網址
    MATCHED_ARXIV,      // 確有收錄且成功提取 arXiv 直達網址
    MATCHED_SPRINGER,   // 確有收錄且成功提取 Springer 直達網址
    MATCHED_ELSEVIER,   // 確有收錄且成功提取 ScienceDirect 直達網址
    MATCHED_OTHER,      // 有此論文，但收錄於其他非主流平台（走全網核對）
    NOT_FOUND,          // ⚠️ 系統查無此文（前端將自動觸發置頂與紅色高亮警告）
    ERROR               // ⚠️ 系統連線異常或解析失敗（前端自動置頂）
}