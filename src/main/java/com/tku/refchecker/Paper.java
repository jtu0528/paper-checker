package com.tku.refchecker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Paper {
    private String rawInput;    // 使用者輸入名稱
    private String foundTitle;  // Gemini 搜尋驗證後的官方全稱
    private String jumpUrl;     // Java 生成的驗證用搜尋連結
    private String status;      // 判斷狀態 (MATCHED, NOT_FOUND, ERROR)
}