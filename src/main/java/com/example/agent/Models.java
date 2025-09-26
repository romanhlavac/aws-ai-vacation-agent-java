package com.example.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class Models {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatRequest {
        @JsonProperty("sessionId")
        public String sessionId;

        @JsonProperty("message")
        public String message;

        @JsonProperty("destination")
        public String destination;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatResponse {
        @JsonProperty("sessionId")
        public String sessionId;
        @JsonProperty("needsDestination")
        public boolean needsDestination;
        @JsonProperty("destinationResolved")
        public String destinationResolved;
        @JsonProperty("reply")
        public String reply;
        @JsonProperty("weather")
        public Object weather;

        public static ChatResponse askForDestination(String sessionId) {
            ChatResponse r = new ChatResponse();
            r.sessionId = sessionId;
            r.needsDestination = true;
            r.reply = "Kam chceš jet na dovolenou? Napiš město nebo místo.";
            return r;
        }
    }

    // --- Geocoding ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeocodingResult {
        public List<GeocodingItem> results;
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GeocodingItem {
        public String name;
        public double latitude;
        public double longitude;
        public String country;
        public String timezone;
    }

    // --- Forecast ---
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ForecastResponse {
        public Daily daily;
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Daily {
            @JsonProperty("time")
            public List<String> dates;
            @JsonProperty("temperature_2m_max")
            public List<Double> tmax;
            @JsonProperty("temperature_2m_min")
            public List<Double> tmin;
            @JsonProperty("precipitation_sum")
            public List<Double> precip;
        }
    }
}
