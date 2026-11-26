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
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    
    // 💡 降低同時查詢數量，避免觸發 Crossref 限流
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    // 💡 Crossref 查詢上限為每秒 1 個請求，所有執行緒共用此間隔（多留 0.1 秒緩衝）
    private static final long CROSSREF_INTERVAL_MS = 1100;
    // 💡 遇到 429 時的重試次數與等待時間
    private static final int CROSSREF_MAX_RETRIES = 2;
    private static final long CROSSREF_RETRY_WAIT_MS = 2000;

    // 💡 DataCite 查詢間隔與重試設定（User-Agent 附 email 時上限為每 5 分鐘 1000 個請求，保守控制在每秒 2 個）
    private static final long DATACITE_INTERVAL_MS = 500;
    private static final int DATACITE_MAX_RETRIES = 2;
    private static final long DATACITE_RETRY_WAIT_MS = 2000;
    private long lastDataciteRequestTime = 0;
    private final Object dataciteLock = new Object();
    private long lastCrossrefRequestTime = 0;

    @GetMapping("/")
    public String index() { return "index"; }

    // 💡 在結果頁按重新整理或從網址列直接開啟時，瀏覽器會以 GET 請求這些路徑，導回首頁避免出現錯誤頁
    @GetMapping({"/check", "/checkMessy", "/checkPdf"})
    public String redirectToIndex() { return "redirect:/"; }

    @PostMapping("/check")
    public String check(@RequestParam("list") String list, Model model) {
        log.info("=== 開始執行 /check (逐行查驗) ===");
        String[] references = list.split("\\r?\\n");

        List<CompletableFuture<Paper>> futures = Arrays.stream(references)
                .map(String::trim)
                .filter(ref -> !ref.isEmpty())
                .map(ref -> CompletableFuture.supplyAsync(() -> processSingleTitle(ref), executor)
                        .orTimeout(3, TimeUnit.MINUTES)
                        .exceptionally(ex -> {
                            log.error("執行緒處理 [{}] 發生錯誤: {}", ref, ex.getMessage());
                            return new Paper(ref, "查驗失敗(執行緒異常)", "#", PaperStatus.ERROR);
                        }))
                .toList();

        List<Paper> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        assignIndex(results);
        sortResultsToTop(results);
        model.addAttribute("groups", List.of(new ResultGroup(null, results)));
        return "index";
    }

    @PostMapping("/checkMessy")
    public String checkMessy(@RequestParam("messyText") String messyText, Model model) {
        log.info("=== 開始執行 /checkMessy ===");
        List<Paper> results = new ArrayList<>();

        try {
            results.addAll(extractAndVerify("混亂、包含換行錯誤或多餘標點", messyText));
        } catch (Exception e) {
            log.error("checkMessy 發生嚴重錯誤: ", e);
            results.add(new Paper("文獻區塊結構解析或網路請求超時", "解析失敗", "#", PaperStatus.ERROR));
        }

        sortResultsToTop(results);
        model.addAttribute("groups", List.of(new ResultGroup(null, results)));
        return "index";
    }

    @PostMapping("/checkPdf")
    public String checkPdf(@RequestParam("pdfFile") List<MultipartFile> files, Model model) {
        log.info("=== 開始執行 /checkPdf（共 {} 份） ===", files.size());
        List<ResultGroup> groups = new ArrayList<>();
        // 💡 多份 PDF 依序處理，每份各自擷取參考文獻與查驗，結果分開顯示
        for (MultipartFile file : files) {
            groups.add(new ResultGroup(file.getOriginalFilename(), checkSinglePdf(file)));
        }
        model.addAttribute("groups", groups);
        return "index";
    }

    /**
     * 處理單一份 PDF：讀取文字、定位參考文獻區塊、擷取並查驗
     */
    private List<Paper> checkSinglePdf(MultipartFile file) {
        log.info("--- 開始處理 PDF: {} ---", file.getOriginalFilename());
        List<Paper> results = new ArrayList<>();
        if (file.isEmpty()) {
            results.add(new Paper("上傳的檔案為空", "解析失敗", "#", PaperStatus.ERROR));
            return results;
        }

        try {
            String fullText;
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(file.getBytes())) {
                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                fullText = stripper.getText(doc);
            }

            // 💡 轉小寫後再搜尋，才能同時找到 References / REFERENCES / references；中文論文另找「參考文獻」
            String lowerText = fullText.toLowerCase(Locale.ROOT);
            int refIndex = Math.max(
                    Math.max(lowerText.lastIndexOf("references"), lowerText.lastIndexOf("bibliography")),
                    Math.max(lowerText.lastIndexOf("參考文獻"), lowerText.lastIndexOf("参考文献")));
            String referenceBlock = (refIndex != -1) ? fullText.substring(refIndex) : fullText;

            results.addAll(extractAndVerify("從 PDF 論文尾頁提取出", referenceBlock));
        } catch (Exception e) {
            log.error("checkPdf 發生嚴重錯誤: ", e);
            results.add(new Paper("PDF 讀取或文獻區塊定位失敗", "解析失敗", "#", PaperStatus.ERROR));
        }

        sortResultsToTop(results);
        return results;
    }

    /**
     * 呼叫 Gemini 從文獻區塊中擷取所有論文標題，再平行查驗每一篇（checkMessy 與 checkPdf 共用）
     * @param sourceDescription 文獻區塊的來源描述（放入 Prompt 中）
     * @param referenceBlock    待解析的文獻區塊
     */
    private List<Paper> extractAndVerify(String sourceDescription, String referenceBlock) throws Exception {
        String prompt = String.format("""
            你是一個精準的學術文獻結構化專家。
            請將以下這段%s的文獻參考資料(References)區塊進行解析。
            請純粹提取出其中所有論文的「完整真實標題」與「第一作者的姓氏」，忽略其他作者、年份、期刊名稱與頁碼。
            請嚴格以 JSON Array 格式回傳，例如：[{"title": "標題1", "firstAuthor": "姓氏1"}, {"title": "標題2", "firstAuthor": "姓氏2"}]
            若無法辨識第一作者，firstAuthor 請填空字串。
            注意：不要包含任何額外的文字說明。
            
            【待解析文獻區塊】：
            %s
            """, sourceDescription, referenceBlock);

        Map<String, Object> bodyMap = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", Map.of("responseMimeType", "application/json")
        );
        
        String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;
        String jsonBody = mapper.writeValueAsString(bodyMap);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(geminiUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
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

        List<Paper> results = new ArrayList<>();
        JsonNode titleArray = mapper.readTree(cleanJson);
        if (titleArray.isArray()) {
            List<CompletableFuture<Paper>> futures = new ArrayList<>();
            for (JsonNode node : titleArray) {
                // 💡 新格式為 {"title": ..., "firstAuthor": ...}；若 Gemini 仍回傳純字串則視為只有標題
                String cleanTitle = node.isObject() ? node.path("title").asText("").trim() : node.asText().trim();
                String firstAuthor = node.isObject() ? node.path("firstAuthor").asText("").trim() : "";
                if (!cleanTitle.isEmpty()) {
                    futures.add(CompletableFuture.supplyAsync(() -> processSingleTitle(cleanTitle, firstAuthor), executor)
                            .orTimeout(3, TimeUnit.MINUTES)
                            .exceptionally(ex -> {
                                log.error("執行緒錯誤 [{}]: {}", cleanTitle, ex.getMessage());
                                return new Paper(cleanTitle, "查驗失敗", "#", PaperStatus.ERROR);
                            }));
                }
            }
            results.addAll(futures.stream().map(CompletableFuture::join).toList());
        }
        assignIndex(results);
        return results;
    }

    /**
     * 模式一（逐行標題）沒有作者資訊，只比對標題
     */
    private Paper processSingleTitle(String rawInput) {
        return processSingleTitle(rawInput, "");
    }

    /**
     * 先查 Crossref；Crossref 判定查無此文時，再查 DataCite 的 arXiv 論文
     * （arXiv 的 DOI 註冊於 DataCite，不在 Crossref）；仍查無則維持 Google Scholar 連結
     * @param firstAuthor 第一作者的姓氏；空字串代表不比對作者
     */
    private Paper processSingleTitle(String rawInput, String firstAuthor) {
        Paper crossrefResult = searchCrossref(rawInput, firstAuthor);
        if (!PaperStatus.NOT_FOUND.name().equals(crossrefResult.getStatus())) {
            return crossrefResult;
        }
        Paper arxivResult = searchDataciteArxiv(rawInput, firstAuthor);
        return arxivResult != null ? arxivResult : crossrefResult;
    }

    /**
     * 查詢 Crossref
     * @param firstAuthor 第一作者的姓氏；空字串代表不比對作者
     */
    private Paper searchCrossref(String rawInput, String firstAuthor) {
        try {
            String encodedTitle = URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
            // 💡 select 只取程式用到的欄位，避免回傳整份參考文獻清單拖慢速度
            String crossrefUrl = "https://api.crossref.org/works?query.title=" + encodedTitle
                    + "&rows=20&select=DOI,title,subtitle,author,publisher";
            // 💡 有第一作者時一併帶入搜尋，讓正確的論文排到前面（請求數不變）
            if (!firstAuthor.isEmpty()) {
                crossrefUrl += "&query.author=" + URLEncoder.encode(firstAuthor, StandardCharsets.UTF_8);
            }
            
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(crossrefUrl))
                    .header("User-Agent", "PaperCheckBot/2.0 (mailto:stats@tku.edu.tw)")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = null;
            // 💡 遇到 429（限流）時等待後重試，最多重試 CROSSREF_MAX_RETRIES 次
            for (int attempt = 0; attempt <= CROSSREF_MAX_RETRIES; attempt++) {
                waitForCrossrefSlot();
                response = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 429) break;
                if (attempt < CROSSREF_MAX_RETRIES) {
                    log.warn("Crossref 限流 (429)，{} 秒後第 {} 次重試 | 標題: [{}]", CROSSREF_RETRY_WAIT_MS / 1000, attempt + 1, rawInput);
                    Thread.sleep(CROSSREF_RETRY_WAIT_MS);
                }
            }
            
            // 💡 Crossref 回應異常（如 429 限流、5xx 伺服器錯誤）時標為 ERROR，避免誤判為查無此文
            if (response.statusCode() != 200) {
                log.warn("Crossref 回應異常，狀態碼: {} | 標題: [{}]", response.statusCode(), rawInput);
                return new Paper(rawInput, "查驗失敗(Crossref 回應異常)", "#", PaperStatus.ERROR);
            }
            
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode items = root.path("message").path("items");
                
                if (items.isArray() && items.size() > 0) {
                    // 💡 從前 20 筆候選中，挑出與輸入相似度最高的那筆
                    JsonNode bestItem = items.get(0);
                    double simScore = -1;
                    // 💡 同時記錄「標題通過門檻且第一作者相符」的最佳候選，避免同名不同作者時選錯篇
                    JsonNode bestAuthorItem = null;
                    double bestAuthorScore = -1;
                    boolean checkAuthor = !firstAuthor.isEmpty();
                    for (JsonNode item : items) {
                        String title = getFullTitle(item);
                        if (title.isEmpty()) continue;
                        double score = calculateSimilarity(rawInput, title);
                        if (score > simScore) {
                            simScore = score;
                            bestItem = item;
                        }
                        if (checkAuthor && score >= 0.75 && score > bestAuthorScore
                                && authorMatches(firstAuthor, getCrossrefFirstAuthor(item))) {
                            bestAuthorScore = score;
                            bestAuthorItem = item;
                        }
                    }
                    if (bestAuthorItem != null) {
                        bestItem = bestAuthorItem;
                        simScore = bestAuthorScore;
                    }
                    
                    String doi = bestItem.path("DOI").asText("");
                    String publisher = bestItem.path("publisher").asText("").toLowerCase();
                    String fullTitle = getFullTitle(bestItem);
                    String officialTitle = fullTitle.isEmpty() ? rawInput : fullTitle;
                    
                    // 💡 加入字串相似度門檻攔截邏輯，並改用 Google Scholar 網址
                    if (simScore < 0.75) {
                        log.warn("❌ 攔截誤判！相似度僅有 {} | 輸入: [{}] | 作者: [{}] | 官方: [{}]", String.format("%.2f", simScore), rawInput, firstAuthor, officialTitle);
                        String scholarSearchUrl = "https://scholar.google.com/scholar?q=" + URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
                        return new Paper(rawInput, rawInput, scholarSearchUrl, PaperStatus.NOT_FOUND);
                    }

                    // 💡 標題通過但第一作者不符（同名不同作者）→ 視為查無此文
                    if (checkAuthor && bestAuthorItem == null) {
                        String crossrefAuthor = getCrossrefFirstAuthor(bestItem);
                        if (!crossrefAuthor.isEmpty()) {
                            log.warn("❌ 作者不符！輸入作者: [{}] | 官方作者: [{}] | 標題: [{}]", firstAuthor, crossrefAuthor, rawInput);
                            String scholarSearchUrl = "https://scholar.google.com/scholar?q=" + URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
                            return new Paper(rawInput, rawInput, scholarSearchUrl, PaperStatus.NOT_FOUND);
                        }
                    }

                    if (!doi.isEmpty()) {
                        String finalJumpUrl = "https://doi.org/" + doi;
                        PaperStatus status = PaperStatus.MATCHED_OTHER;

                        if (publisher.contains("ieee") || doi.startsWith("10.1109")) {
                            status = PaperStatus.MATCHED_IEEE;
                        } else if (publisher.contains("computing machinery") || publisher.contains("acm") || doi.startsWith("10.1145")) {
                            status = PaperStatus.MATCHED_ACM;
                        } else if (publisher.contains("springer") || doi.startsWith("10.1007")) {
                            status = PaperStatus.MATCHED_SPRINGER;
                        } else if (publisher.contains("elsevier") || publisher.contains("science-direct") || doi.startsWith("10.1016")) {
                            status = PaperStatus.MATCHED_ELSEVIER;
                        } else if (publisher.contains("arxiv")) {
                            status = PaperStatus.MATCHED_ARXIV;
                        }

                        return new Paper(rawInput, officialTitle, finalJumpUrl, status);
                    }
                }
            }
            
            // 💡 完全找不到時，同樣改用 Google Scholar 網址
            String scholarSearchUrl = "https://scholar.google.com/scholar?q=" + URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
            return new Paper(rawInput, rawInput, scholarSearchUrl, PaperStatus.NOT_FOUND);

        } catch (Exception e) {
            log.error("processSingleTitle 發生異常, 標題 [{}]: {}", rawInput, e.getMessage());
            return new Paper(rawInput, "查驗失敗或網路超時", "#", PaperStatus.ERROR);
        }
    }

    /**
     * 查詢 DataCite 中的 arXiv 論文（限定 arXiv 的 DOI 前綴 10.48550，涵蓋所有領域的 arXiv 論文）
     * - 找到相符論文：回傳 MATCHED_ARXIV
     * - 查無或作者不符：回傳 null，沿用 Crossref 的查無結果
     * - DataCite 回應異常：回傳 ERROR，避免誤判為查無此文
     */
    private Paper searchDataciteArxiv(String rawInput, String firstAuthor) {
        try {
            // 💡 移除標點符號並轉小寫，避免與搜尋語法（AND、OR、括號、冒號等）衝突
            String cleaned = rawInput.replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim().toLowerCase(Locale.ROOT);
            String dataciteUrl = "https://api.datacite.org/dois?prefix=10.48550&page%5Bsize%5D=10&query="
                    + URLEncoder.encode("titles.title:(" + cleaned + ")", StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(dataciteUrl))
                    .header("User-Agent", "PaperCheckBot/2.0 (mailto:stats@tku.edu.tw)")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = null;
            for (int attempt = 0; attempt <= DATACITE_MAX_RETRIES; attempt++) {
                waitForDataciteSlot();
                response = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 429) break;
                if (attempt < DATACITE_MAX_RETRIES) {
                    log.warn("DataCite 限流 (429)，{} 秒後第 {} 次重試 | 標題: [{}]", DATACITE_RETRY_WAIT_MS / 1000, attempt + 1, rawInput);
                    Thread.sleep(DATACITE_RETRY_WAIT_MS);
                }
            }

            if (response.statusCode() != 200) {
                log.warn("DataCite 回應異常，狀態碼: {} | 標題: [{}]", response.statusCode(), rawInput);
                return new Paper(rawInput, "查驗失敗(DataCite 回應異常)", "#", PaperStatus.ERROR);
            }

            JsonNode data = mapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() == 0) {
                log.warn("❌ arXiv 亦查無此文 | 輸入: [{}]", rawInput);
                return null;
            }

            // 💡 比對規則與 Crossref 相同：相似度門檻 0.75，第一作者相符者優先
            JsonNode bestAttr = null;
            double bestScore = -1;
            JsonNode bestAuthorAttr = null;
            double bestAuthorScore = -1;
            boolean checkAuthor = !firstAuthor.isEmpty();
            for (JsonNode item : data) {
                JsonNode attr = item.path("attributes");
                String title = attr.path("titles").path(0).path("title").asText("");
                if (title.isEmpty()) continue;
                double score = calculateSimilarity(rawInput, title);
                if (score > bestScore) {
                    bestScore = score;
                    bestAttr = attr;
                }
                if (checkAuthor && score >= 0.75 && score > bestAuthorScore
                        && authorMatches(firstAuthor, getDataciteFirstAuthor(attr))) {
                    bestAuthorScore = score;
                    bestAuthorAttr = attr;
                }
            }
            if (bestAuthorAttr != null) {
                bestAttr = bestAuthorAttr;
                bestScore = bestAuthorScore;
            }

            if (bestAttr == null || bestScore < 0.75) {
                log.warn("❌ arXiv 亦查無此文 | 相似度僅有 {} | 輸入: [{}]", String.format("%.2f", bestScore), rawInput);
                return null;
            }
            if (checkAuthor && bestAuthorAttr == null && !getDataciteFirstAuthor(bestAttr).isEmpty()) {
                log.warn("❌ arXiv 作者不符！輸入作者: [{}] | 官方作者: [{}] | 標題: [{}]", firstAuthor, getDataciteFirstAuthor(bestAttr), rawInput);
                return null;
            }

            String officialTitle = bestAttr.path("titles").path(0).path("title").asText(rawInput);
            String doi = bestAttr.path("doi").asText("");
            String jumpUrl = bestAttr.path("url").asText("");
            if (jumpUrl.isEmpty()) {
                jumpUrl = doi.isEmpty() ? "#" : "https://doi.org/" + doi;
            }

            log.info("✅ arXiv 補查成功 | 輸入: [{}] | 官方: [{}]", rawInput, officialTitle);
            return new Paper(rawInput, officialTitle, jumpUrl, PaperStatus.MATCHED_ARXIV);

        } catch (Exception e) {
            log.error("searchDataciteArxiv 發生異常, 標題 [{}]: {}", rawInput, e.getMessage());
            return new Paper(rawInput, "查驗失敗(DataCite 回應異常)", "#", PaperStatus.ERROR);
        }
    }

    /**
     * 取出 DataCite 資料中第一作者的姓氏：優先用 familyName；
     * 沒有時改用 name（格式通常為「姓, 名」，取逗號前的部分）
     */
    private String getDataciteFirstAuthor(JsonNode attr) {
        JsonNode first = attr.path("creators").path(0);
        String family = first.path("familyName").asText("");
        if (!family.isEmpty()) return family;
        String name = first.path("name").asText("");
        int comma = name.indexOf(',');
        return comma > 0 ? name.substring(0, comma) : name;
    }

    /**
     * 所有執行緒共用：確保兩次 DataCite 請求之間至少間隔 DATACITE_INTERVAL_MS
     * （使用獨立的鎖，不會和 Crossref 的等待互相卡住）
     */
    private void waitForDataciteSlot() throws InterruptedException {
        synchronized (dataciteLock) {
            long waitMs = lastDataciteRequestTime + DATACITE_INTERVAL_MS - System.currentTimeMillis();
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
            lastDataciteRequestTime = System.currentTimeMillis();
        }
    }

    /**
     * 所有執行緒共用：確保兩次 Crossref 請求之間至少間隔 CROSSREF_INTERVAL_MS，避免觸發限流（429）
     */
    private synchronized void waitForCrossrefSlot() throws InterruptedException {
        long waitMs = lastCrossrefRequestTime + CROSSREF_INTERVAL_MS - System.currentTimeMillis();
        if (waitMs > 0) {
            Thread.sleep(waitMs);
        }
        lastCrossrefRequestTime = System.currentTimeMillis();
    }

    /**
     * 取出 Crossref 資料的完整標題：若有副標題（subtitle）則接在主標題後面
     * （ACM 等出版商常將「名稱: 副標題」拆成 title 與 subtitle 兩個欄位）；
     * 主標題已包含副標題時不重複接上
     */
    private String getFullTitle(JsonNode item) {
        String title = item.path("title").path(0).asText("");
        String subtitle = item.path("subtitle").path(0).asText("");
        if (!title.isEmpty() && !subtitle.isEmpty()
                && !title.toLowerCase(Locale.ROOT).contains(subtitle.toLowerCase(Locale.ROOT))) {
            title = title + ": " + subtitle;
        }
        return title;
    }

    /**
     * 取出 Crossref 資料中第一作者的姓氏（優先取 sequence 為 first 者）；沒有作者資料時回傳空字串
     */
    private String getCrossrefFirstAuthor(JsonNode item) {
        JsonNode authors = item.path("author");
        if (!authors.isArray() || authors.size() == 0) return "";
        JsonNode first = authors.get(0);
        for (JsonNode a : authors) {
            if ("first".equals(a.path("sequence").asText())) {
                first = a;
                break;
            }
        }
        String family = first.path("family").asText("");
        return family.isEmpty() ? first.path("name").asText("") : family;
    }

    /**
     * 比對第一作者姓氏：忽略大小寫、重音符號與標點；完全相同或其中一方以另一方結尾即視為相符
     * （例如 "A. Smith" 與 "Smith"、"van der Aalst" 與 "Aalst"；"Li" 與 "Lin" 則不相符）
     */
    private boolean authorMatches(String inputAuthor, String crossrefAuthor) {
        String a = normalizeName(inputAuthor);
        String b = normalizeName(crossrefAuthor);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.endsWith(b) || b.endsWith(a);
    }

    private String normalizeName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]", "");
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

    /**
     * 依原始輸入順序編號（從 1 開始），需在排序之前呼叫，排序後仍可對回原文的第幾篇
     */
    private void assignIndex(List<Paper> results) {
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setIndex(i + 1);
        }
    }

    private void sortResultsToTop(List<Paper> results) {
        results.sort((p1, p2) -> {
            boolean p1IsProblem = PaperStatus.NOT_FOUND.name().equals(p1.getStatus()) || PaperStatus.ERROR.name().equals(p1.getStatus());
            boolean p2IsProblem = PaperStatus.NOT_FOUND.name().equals(p2.getStatus()) || PaperStatus.ERROR.name().equals(p2.getStatus());
            if (p1IsProblem && !p2IsProblem) return -1;
            if (!p1IsProblem && p2IsProblem) return 1;
            return 0;
        });
    }
}