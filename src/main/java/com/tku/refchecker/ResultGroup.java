package com.tku.refchecker;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 一組查驗結果：PDF 模式下每份檔案一組；逐行查驗與混亂文獻模式只有一組（fileName 為 null）
 */
@Data
@AllArgsConstructor
public class ResultGroup {

    private String fileName;      // 來源 PDF 檔名；非 PDF 模式為 null（前端不顯示檔名標題）

    private List<Paper> results;  // 該組的查驗結果（已依異常置頂排序）
}