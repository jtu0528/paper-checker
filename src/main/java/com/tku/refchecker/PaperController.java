package com.tku.refchecker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/check")
    public String check(@RequestParam("list") String list, Model model) {
        List<Paper> results = new ArrayList<>();
        String[] references = list.split("\\r?\\n");
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        String geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=" + geminiApiKey;

        for (String ref : references) {
            String rawInput = ref.trim();
            if (rawInput.isEmpty()) continue;

            try {
                // 💡 1. 生成 IEEE 站內搜尋連結
                String ieeeQuery = "site:ieeexplore.ieee.org \"" + rawInput + "\"";
                String ieeeJumpUrl = "https://www.google.com/search?q=" + URLEncoder.encode(ieeeQuery, StandardCharsets.UTF_8);

                // 💡 2. 修正 Prompt：確保 JSON 鍵值與 Java 變數名稱完全一致
                String prompt = String.format("""
                    請作為一名『即時學術核稿員』。
                    【輸入名稱】： %s
                    【目標驗證網址】： %s
                    
                    任務要求：
                    1. 請利用 Google 搜尋功能，實際查看該【目標驗證網址】在搜尋結果中的內容。
                    2. 比對該網址指向的論文標題與【輸入名稱】是否指向同一篇研究。
                    3. 只要輸入的內容是該論文的「關鍵字」或「部分標題」，都視為存在。
                    4. 如果存在，請抓取該頁面顯示的『官方完整全稱』。
                    5. 判定這篇論文是否確實收錄在 IEEE Xplore。
                    
                    請嚴格回傳 JSON (鍵值名稱必須精確)：
                    {"exists": true, "officialTitle": "抓取到的官方完整標題", "onIeee": true}
                    """, rawInput, ieeeJumpUrl);

                Map<String, Object> bodyMap = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
                String jsonBody = mapper.writeValueAsString(bodyMap);

                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(geminiUrl)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
                HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
                
                String aiText = mapper.readTree(response.body()).path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
                JsonNode result = mapper.readTree(aiText.replaceAll("```json|```", "").trim());

                // ✅ 變數名稱對齊 (exists, onIeee, officialTitle)
                boolean exists = result.path("exists").asBoolean(); 
                boolean onIeee = result.path("onIeee").asBoolean();
                String officialTitle = result.path("officialTitle").asText();

                String finalJumpUrl;
                String status;

                // 💡 3. 分流邏輯
                if (exists && onIeee) {
                    finalJumpUrl = ieeeJumpUrl; // 採用 Java 原本生成的網址
                    status = "MATCHED_IEEE";
                } else if (exists) {
                    // 若不在 IEEE，則搜尋該官方全稱做全網核對
                    String generalQuery = "\"" + officialTitle + "\"";
                    finalJumpUrl = "https://www.google.com/search?q=" + URLEncoder.encode(generalQuery, StandardCharsets.UTF_8);
                    status = "MATCHED_OTHER";
                } else {
                    finalJumpUrl = "https://www.google.com/search?q=" + URLEncoder.encode(rawInput, StandardCharsets.UTF_8);
                    status = "NOT_FOUND";
                }

                results.add(new Paper(rawInput, officialTitle, finalJumpUrl, status));
                Thread.sleep(2000); 

            } catch (Exception e) {
                results.add(new Paper(rawInput, "查驗失敗", "#", "ERROR"));
            }
        }
        model.addAttribute("results", results);
        return "index";
    }
}