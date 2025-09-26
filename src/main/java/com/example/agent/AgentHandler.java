package com.example.agent;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class AgentHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPEN_METEO_GEOCODING = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=cs&name=";
    private static final String OPEN_METEO_FORECAST = "https://api.open-meteo.com/v1/forecast?daily=temperature_2m_max,temperature_2m_min,precipitation_sum&forecast_days=7&timezone=auto&latitude=%s&longitude=%s";

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            Models.ChatRequest req = parseBody(input.getBody());
            String sessionId = req.sessionId != null ? req.sessionId : UUID.randomUUID().toString();

            // 1) Zjisti destinaci (priorita: explicitní pole -> LLM -> fallback)
            String destination = normalize(req.destination);
            if (isBlank(destination)) {
                destination = extractDestinationWithBedrock(req.message);
            }
            if (isBlank(destination)) {
                return ok(Models.ChatResponse.askForDestination(sessionId));
            }

            // 2) Geocoding (Open-Meteo)
            Models.GeocodingItem place = geocode(destination);
            if (place == null) {
                Models.ChatResponse r = new Models.ChatResponse();
                r.sessionId = sessionId;
                r.needsDestination = true;
                r.destinationResolved = null;
                r.reply = "Nenašel jsem tu destinaci. Zkus prosím konkrétnější název města/místa.";
                return ok(r);
            }

            // 3) Forecast
            Models.ForecastResponse forecast = forecast(place.latitude, place.longitude);

            // 4) Vytvoř souhrn
            String summary = formatSummary(place, forecast);

            Models.ChatResponse r = new Models.ChatResponse();
            r.sessionId = sessionId;
            r.needsDestination = false;
            r.destinationResolved = place.name + (place.country != null ? ", " + place.country : "");
            r.reply = summary;
            r.weather = forecast; // surová data pro klienta
            return ok(r);

        } catch (Exception e) {
            e.printStackTrace();
            return error(500, "Internal error: " + e.getMessage());
        }
    }

    private Models.ChatRequest parseBody(String body) throws Exception {
        if (body == null || body.isBlank()) return new Models.ChatRequest();
        return MAPPER.readValue(body, Models.ChatRequest.class);
    }

    private String normalize(String s) {
        return (s == null) ? null : s.trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // === 1) Extrakce destinace pomocí LLM (Bedrock Anthropic messages) ===
    private String extractDestinationWithBedrock(String userMessage) {
        try {
            if (isBlank(userMessage)) return null;
            String modelId = System.getenv("BEDROCK_MODEL_ID");
            if (modelId == null || modelId.isBlank()) {
                // Fallback bez LLM: vezmi první slovo s velkým písmenem a bez diakritiky to zkus (velmi jednoduché).
                return simpleHeuristic(userMessage);
            }

            String systemPrompt = "You extract exactly ONE travel destination (city or place name) from the user's message. "
                    + "Return only the destination name (no extra words, no quotes). If no destination is present, return NONE. "
                    + "Accept Czech language too.";

            // Anthropic messages schema (Bedrock)
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("anthropic_version", "bedrock-2023-05-31");
            payload.put("system", systemPrompt);

            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            List<Map<String, String>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", userMessage));
            userMsg.put("content", content);

            payload.put("messages", List.of(userMsg));
            payload.put("max_tokens", 128);

            String json = MAPPER.writeValueAsString(payload);

            BedrockRuntimeClient bedrock = BedrockClientFactory.create();
            InvokeModelResponse resp = bedrock.invokeModel(InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(json))
                    .build());

            String responseJson = resp.body().asUtf8String();
            JsonNode root = MAPPER.readTree(responseJson);
            // Expected: { content: [ { text: "Prague" } ], ... }
            String extracted = null;
            if (root.has("content") && root.get("content").isArray() && root.get("content").size() > 0) {
                JsonNode first = root.get("content").get(0);
                if (first.has("text")) {
                    extracted = first.get("text").asText();
                }
            }
            if (extracted != null) {
                extracted = extracted.trim();
                if ("NONE".equalsIgnoreCase(extracted)) return null;
                // Ořízni případné tečky/uvozovky
                extracted = extracted.replaceAll("^[\"'“”„\\s]+|[\"'“”„\\s]+$", "");
                return extracted;
            }
            return null;
        } catch (Exception e) {
            // fallback
            return simpleHeuristic(userMessage);
        }
    }

    private String simpleHeuristic(String text) {
        // velmi jednoduché: najdi "do {Slovo}" / "to {Word}" / první kapitalizované slovo
        String t = text.trim();
        String[] tokens = t.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equalsIgnoreCase("do") || tokens[i].equalsIgnoreCase("to")) {
                String cand = tokens[i + 1].replaceAll("[,.;:!?]", "");
                if (cand.length() > 1) return cand;
            }
        }
        for (String tok : tokens) {
            if (Character.isUpperCase(tok.codePointAt(0))) {
                String cand = tok.replaceAll("[,.;:!?]", "");
                if (cand.length() > 1) return cand;
            }
        }
        return null;
    }

    // === 2) Geocoding ===
    private Models.GeocodingItem geocode(String destination) throws Exception {
        String url = OPEN_METEO_GEOCODING + URLEncoder.encode(destination, StandardCharsets.UTF_8);
        String json = HttpUtil.get(url);
        Models.GeocodingResult r = MAPPER.readValue(json, Models.GeocodingResult.class);
        if (r.results == null || r.results.isEmpty()) return null;
        return r.results.get(0);
    }

    // === 3) Forecast ===
    private Models.ForecastResponse forecast(double lat, double lon) throws Exception {
        String url = String.format(OPEN_METEO_FORECAST, lat, lon);
        String json = HttpUtil.get(url);
        return MAPPER.readValue(json, Models.ForecastResponse.class);
    }

    private String formatSummary(Models.GeocodingItem place, Models.ForecastResponse f) {
        if (f == null || f.daily == null || f.daily.dates == null) {
            return "Počasí se nepodařilo načíst.";
        }
        String header = String.format("Předpověď na 7 dní pro %s (%.3f, %.3f)%s:",
                place.name, place.latitude, place.longitude,
                place.country != null ? ", " + place.country : "");

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < f.daily.dates.size(); i++) {
            String date = f.daily.dates.get(i);
            Double tmax = safeGet(f.daily.tmax, i);
            Double tmin = safeGet(f.daily.tmin, i);
            Double pr = safeGet(f.daily.precip, i);
            lines.add(String.format("- %s: max %.0f°C / min %.0f°C, srážky %.1f mm",
                    date, tmax, tmin, pr));
        }
        return header + "\n" + String.join("\n", lines);
    }

    private Double safeGet(List<Double> list, int i) {
        if (list == null || i >= list.size()) return Double.NaN;
        return list.get(i);
    }

    private APIGatewayProxyResponseEvent ok(Object body) throws Exception {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
                .withBody(MAPPER.writeValueAsString(body));
        }

    private APIGatewayProxyResponseEvent error(int code, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
                .withBody("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
