package com.tku.refchecker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Controller
public class PaperController {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @GetMapping("/")
    public String index() { return "index"; }

    /**
     * 🎯 功能一：逐行標題查驗
     */
    @PostMapping("/check")
    public String check(@RequestParam("list") String list, Model model) {
        List<Paper> results = new ArrayList<>();
        String[] references = list.split("\\r?\\n");
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        for (String ref : references) {
            String rawInput = ref.trim();
            if (rawInput.isEmpty()) continue;

            Paper p = processSingleTitle(rawInput, client, mapper);
            results.add(p);
        }

        sortResultsToTop(results);
        model.addAttribute("results", results);
        return "index";
    }

    /**
     * 🚀 功能二：整串混亂的 Reference 區塊貼入查詢 (含強制 JSON 模式防禦)
     */
    @PostMapping("/checkMessy")
    public String checkMessy(@RequestParam("messyText") String messyText, Model model) {
        List<Paper> results = new ArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        try {
            String prompt = String.format("""
                你是一個精準的學術文獻結構化專家。
                請將以下這段混亂、包含換行錯誤或多餘標點的文獻參考資料(References)區塊進行解析。
                請純粹提取出其中所有論文的「完整真實標題」，忽略作者、年份、期刊名稱與頁碼。
                請嚴格以 JSON Array 格式回傳，例如：["標題1", "標題2"]
                注意：不要包含任何額外的文字說明。
                
                【待解析文獻區塊】：
                %s
                """, messyText);

            Map<String, Object> bodyMap = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json")
            );
            
            String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=" + geminiApiKey;
            String jsonBody = mapper.writeValueAsString(bodyMap);

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(geminiUrl)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            
            String aiText = mapper.readTree(response.body()).path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
            
            String cleanJson = aiText.trim();
            int startIdx = cleanJson.indexOf("[");
            int endIdx = cleanJson.lastIndexOf("]");
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                cleanJson = cleanJson.substring(startIdx, endIdx + 1);
            }

            JsonNode titleArray = mapper.readTree(cleanJson);

            if (titleArray.isArray()) {
                for (JsonNode node : titleArray) {
                    String cleanTitle = node.asText().trim();
                    if (!cleanTitle.isEmpty()) {
                        results.add(processSingleTitle(cleanTitle, client, mapper));
                    }
                }
            }
        } catch (Exception e) {
            results.add(new Paper("文獻區塊結構解析失敗", "解析失敗", "#", "ERROR"));
        }

        sortResultsToTop(results);
        model.addAttribute("results", results);
        return "index";
    }

    /**
     * 🚀 功能三：PDF 檔案上傳全自動校核
     */
    @PostMapping("/checkPdf")
    public String checkPdf(@RequestParam("pdfFile") MultipartFile file, Model model) {
        List<Paper> results = new ArrayList<>();
        if (file.isEmpty()) {
            results.add(new Paper("上傳的檔案為空", "解析失敗", "#", "ERROR"));
            model.addAttribute("results", results);
            return "index";
        }

        try {
            String fullText;
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(file.getBytes())) {
                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                fullText = stripper.getText(doc);
            }

            int refIndex = Math.max(fullText.lastIndexOf("References"), fullText.lastIndexOf("Bibliography"));
            String referenceBlock = (refIndex != -1) ? fullText.substring(refIndex) : fullText;

            HttpClient client = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();

            String prompt = String.format("""
                你是一個精準的學術文獻結構化專家。
                請將以下這段從 PDF 論文尾頁提取出的文獻參考資料(References)區塊進行解析。
                請純粹提取出其中所有論文的「完整真實標題」，忽略作者、年份、期刊名稱與頁碼。
                請嚴格以 JSON Array 格式回傳，例如：["標題1", "標題2"]
                注意：不要包含任何額外的文字說明。
                
                【待解析文獻區塊】：
                %s
                """, referenceBlock);

            Map<String, Object> bodyMap = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json")
            );
            
            String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=" + geminiApiKey;
            String jsonBody = mapper.writeValueAsString(bodyMap);

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(geminiUrl)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            
            String aiText = mapper.readTree(response.body()).path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
            
            String cleanJson = aiText.trim();
            int startIdx = cleanJson.indexOf("[");
            int endIdx = cleanJson.lastIndexOf("]");
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                cleanJson = cleanJson.substring(startIdx, endIdx + 1);
            }

            JsonNode titleArray = mapper.readTree(cleanJson);

            if (titleArray.isArray()) {
                for (JsonNode node : titleArray) {
                    String cleanTitle = node.asText().trim();
                    if (!cleanTitle.isEmpty()) {
                        results.add(processSingleTitle(cleanTitle, client, mapper));
                    }
                }
            }
        } catch (Exception e) {
            results.add(new Paper("PDF 讀取或文獻區塊定位失敗", "解析失敗", "#", "ERROR"));
        }

        sortResultsToTop(results);
        model.addAttribute("results", results);
        return "index";
    }

    /**
     * 🛠️ 核心處理：利用 Crossref 標題專用路由進行「精準多網域識別」
     */
    private Paper processSingleTitle(String rawInput, HttpClient client, ObjectMapper mapper) {
        try {
            String encodedTitle = URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
            
            // 💡 關鍵優化：將原本的 query= 改為 query.title= 
            // 這會強迫 Crossref 排除摘要和内文雜訊，只做高精確度的論文標題比對！
            String crossrefUrl = "https://api.crossref.org/works?query.title=" + encodedTitle + "&rows=1";
            
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(crossrefUrl))
                    .header("User-Agent", "PaperCheckBot/2.0 (mailto:stats@tku.edu.tw)")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode items = root.path("message").path("items");
                
                if (items.isArray() && items.size() > 0) {
                    JsonNode firstItem = items.get(0);
                    
                    String doi = firstItem.path("DOI").asText("");
                    String publisher = firstItem.path("publisher").asText("").toLowerCase();
                    String officialTitle = firstItem.path("title").path(0).asText(rawInput);
                    
                    if (!doi.isEmpty()) {
                        String finalJumpUrl = "https://doi.org/" + doi;
                        String status = "MATCHED_OTHER"; // 預設

                        // 🔍 工業級多網域自動識別機制
                        if (publisher.contains("ieee") || doi.startsWith("10.1109")) {
                            status = "MATCHED_IEEE";
                        } else if (publisher.contains("computing machinery") || publisher.contains("acm") || doi.startsWith("10.1145")) {
                            status = "MATCHED_ACM";
                        } else if (publisher.contains("springer") || doi.startsWith("10.1007")) {
                            status = "MATCHED_SPRINGER";
                        } else if (publisher.contains("elsevier") || publisher.contains("science-direct") || doi.startsWith("10.1016")) {
                            status = "MATCHED_ELSEVIER";
                        } else if (publisher.contains("arxiv")) {
                            status = "MATCHED_ARXIV";
                        }

                        Thread.sleep(100); 
                        return new Paper(rawInput, officialTitle, finalJumpUrl, status);
                    }
                }
            }
            
            String googleSearchUrl = "https://www.google.com/search?q=" + URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
            return new Paper(rawInput, rawInput, googleSearchUrl, "NOT_FOUND");

        } catch (Exception e) {
            e.printStackTrace();
            return new Paper(rawInput, "查驗失敗", "#", "ERROR");
        }
    }

    /**
     * 🛠️ 輔助工具：排序演算法 (Exception-First 置頂)
     */
    private void sortResultsToTop(List<Paper> results) {
        results.sort((p1, p2) -> {
            boolean p1IsProblem = "NOT_FOUND".equals(p1.getStatus()) || "ERROR".equals(p1.getStatus());
            boolean p2IsProblem = "NOT_FOUND".equals(p2.getStatus()) || "ERROR".equals(p2.getStatus());
            if (p1IsProblem && !p2IsProblem) return -1;
            if (!p1IsProblem && p2IsProblem) return 1;
            return 0;
        });
    }
}