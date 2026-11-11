package com.tku.refchecker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Controller
public class PaperController {

    private static final Logger log = LoggerFactory.getLogger(PaperController.class);

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    // 💡 預設模型改為官方絕對支援的穩定版
    @Value("${gemini.api.model}")
    private String geminiModel;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @GetMapping("/")
    public String index() { return "index"; }

    @PostMapping("/check")
    public String check(@RequestParam("list") String list, Model model) {
        log.info("=== 開始執行 /check (逐行查驗) ===");
        String[] references = list.split("\\r?\\n");

        List<CompletableFuture<Paper>> futures = Arrays.stream(references)
                .map(String::trim)
                .filter(ref -> !ref.isEmpty())
                .map(ref -> CompletableFuture.supplyAsync(() -> processSingleTitle(ref), executor)
                        .exceptionally(ex -> {
                            log.error("執行緒處理 [{}] 發生錯誤: {}", ref, ex.getMessage());
                            return new Paper(ref, "查驗失敗(執行緒異常)", "#", "ERROR");
                        }))
                .toList();

        List<Paper> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        sortResultsToTop(results);
        model.addAttribute("results", results);
        return "index";
    }

    @PostMapping("/checkMessy")
    public String checkMessy(@RequestParam("messyText") String messyText, Model model) {
        log.info("=== 開始執行 /checkMessy ===");
        List<Paper> results = new ArrayList<>();

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
            
            String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;
            String jsonBody = mapper.writeValueAsString(bodyMap);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(geminiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
                    
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Gemini API 請求失敗，狀態碼: {}", response.statusCode());
                throw new RuntimeException("Gemini API 狀態碼異常");
            }
            
            String aiText = mapper.readTree(response.body()).path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
            
            String cleanJson = aiText.trim();
            int startIdx = cleanJson.indexOf("[");
            int endIdx = cleanJson.lastIndexOf("]");
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                cleanJson = cleanJson.substring(startIdx, endIdx + 1);
            }

            JsonNode titleArray = mapper.readTree(cleanJson);
            if (titleArray.isArray()) {
                List<CompletableFuture<Paper>> futures = new ArrayList<>();
                for (JsonNode node : titleArray) {
                    String cleanTitle = node.asText().trim();
                    if (!cleanTitle.isEmpty()) {
                        futures.add(CompletableFuture.supplyAsync(() -> processSingleTitle(cleanTitle), executor)
                                .exceptionally(ex -> {
                                    log.error("執行緒錯誤 [{}]: {}", cleanTitle, ex.getMessage());
                                    return new Paper(cleanTitle, "查驗失敗", "#", "ERROR");
                                }));
                    }
                }
                results.addAll(futures.stream().map(CompletableFuture::join).toList());
            }
        } catch (Exception e) {
            log.error("checkMessy 發生嚴重錯誤: ", e);
            results.add(new Paper("文獻區塊結構解析或網路請求超時", "解析失敗", "#", "ERROR"));
        }

        sortResultsToTop(results);
        model.addAttribute("results", results);
        return "index";
    }

    @PostMapping("/checkPdf")
    public String checkPdf(@RequestParam("pdfFile") MultipartFile file, Model model) {
        log.info("=== 開始執行 /checkPdf ===");
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
            
            String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;
            String jsonBody = mapper.writeValueAsString(bodyMap);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(geminiUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
                    
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Gemini API 請求失敗，狀態碼: {}", response.statusCode());
                throw new RuntimeException("Gemini API 狀態碼異常");
            }
            
            String aiText = mapper.readTree(response.body()).path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
            
            String cleanJson = aiText.trim();
            int startIdx = cleanJson.indexOf("[");
            int endIdx = cleanJson.lastIndexOf("]");
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                cleanJson = cleanJson.substring(startIdx, endIdx + 1);
            }

            JsonNode titleArray = mapper.readTree(cleanJson);
            if (titleArray.isArray()) {
                List<CompletableFuture<Paper>> futures = new ArrayList<>();
                for (JsonNode node : titleArray) {
                    String cleanTitle = node.asText().trim();
                    if (!cleanTitle.isEmpty()) {
                        futures.add(CompletableFuture.supplyAsync(() -> processSingleTitle(cleanTitle), executor)
                                .exceptionally(ex -> {
                                    log.error("執行緒錯誤 [{}]: {}", cleanTitle, ex.getMessage());
                                    return new Paper(cleanTitle, "查驗失敗", "#", "ERROR");
                                }));
                    }
                }
                results.addAll(futures.stream().map(CompletableFuture::join).toList());
            }
        } catch (Exception e) {
            log.error("checkPdf 發生嚴重錯誤: ", e);
            results.add(new Paper("PDF 讀取或文獻區塊定位失敗", "解析失敗", "#", "ERROR"));
        }

        sortResultsToTop(results);
        model.addAttribute("results", results);
        return "index";
    }

    private Paper processSingleTitle(String rawInput) {
        try {
            String encodedTitle = URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
            String crossrefUrl = "https://api.crossref.org/works?query.title=" + encodedTitle + "&rows=5";
            
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(crossrefUrl))
                    .header("User-Agent", "PaperCheckBot/2.0 (mailto:stats@tku.edu.tw)")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode items = root.path("message").path("items");
                
                if (items.isArray() && items.size() > 0) {
                    // 💡 從前 5 筆候選中，挑出與輸入相似度最高的那筆
                    JsonNode bestItem = items.get(0);
                    double simScore = -1;
                    for (JsonNode item : items) {
                        String title = item.path("title").path(0).asText("");
                        if (title.isEmpty()) continue;
                        double score = calculateSimilarity(rawInput, title);
                        if (score > simScore) {
                            simScore = score;
                            bestItem = item;
                        }
                    }
                    
                    String doi = bestItem.path("DOI").asText("");
                    String publisher = bestItem.path("publisher").asText("").toLowerCase();
                    String officialTitle = bestItem.path("title").path(0).asText(rawInput);
                    
                    // 💡 加入字串相似度門檻攔截邏輯，並改用 Google Scholar 網址
                    if (simScore < 0.75) {
                        log.warn("❌ 攔截誤判！相似度僅有 {} | 輸入: [{}] | 官方: [{}]", String.format("%.2f", simScore), rawInput, officialTitle);
                        String scholarSearchUrl = "https://scholar.google.com/scholar?q=" + URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
                        return new Paper(rawInput, rawInput, scholarSearchUrl, "NOT_FOUND");
                    }

                    if (!doi.isEmpty()) {
                        String finalJumpUrl = "https://doi.org/" + doi;
                        String status = "MATCHED_OTHER";

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

                        Thread.sleep(50); 
                        return new Paper(rawInput, officialTitle, finalJumpUrl, status);
                    }
                }
            }
            
            // 💡 完全找不到時，同樣改用 Google Scholar 網址
            String scholarSearchUrl = "https://scholar.google.com/scholar?q=" + URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
            return new Paper(rawInput, rawInput, scholarSearchUrl, "NOT_FOUND");

        } catch (Exception e) {
            log.error("processSingleTitle 發生異常, 標題 [{}]: {}", rawInput, e.getMessage());
            return new Paper(rawInput, "查驗失敗或網路超時", "#", "ERROR");
        }
    }

    private double calculateSimilarity(String s1, String s2) {
        String longer = s1.toLowerCase().replaceAll("[^a-z0-9]", "");
        String shorter = s2.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (longer.length() < shorter.length()) {
            String temp = longer; longer = shorter; shorter = temp;
        }
        int longerLength = longer.length();
        if (longerLength == 0) return 1.0;
        return (longerLength - editDistance(longer, shorter)) / (double) longerLength;
    }

    private int editDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) costs[j] = j;
                else {
                    if (j > 0) {
                        int newValue = costs[j - 1];
                        if (s1.charAt(i - 1) != s2.charAt(j - 1))
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) costs[s2.length()] = lastValue;
        }
        return costs[s2.length()];
    }

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