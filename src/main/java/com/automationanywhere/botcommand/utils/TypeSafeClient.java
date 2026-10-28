package com.automationanywhere.botcommand.utils;

import com.automationanywhere.botcommand.exception.BotCommandException;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class TypeSafeClient implements TypeSafeDecisionSource {

    private static final Logger logger = LogManager.getLogger(TypeSafeClient.class);
    private static final Gson GSON = new Gson();
    private static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    private static final int MAX_ATTEMPTS = 2;

    private final String apiKey;
    private final String baseUrl;
    private final HttpClient httpClient;

    public TypeSafeClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public TypeSafeClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public TypeSafeTypes.Response classify(TypeSafeTypes.Request request) {
        String body = GSON.toJson(request);
        Exception lastTransientError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> resp;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/systemone"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(15))
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (HttpTimeoutException e) {
                lastTransientError = e;
                logger.warn("TypeSafe call attempt {} of {} timed out, retrying", attempt, MAX_ATTEMPTS);
                continue;
            } catch (Exception e) {
                throw new BotCommandException("Failed to call TypeSafe: " + e.getMessage(), e);
            }

            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                throw new BotCommandException(
                        "TypeSafe rejected the API key (HTTP " + resp.statusCode() + "). Check the mapped credential.");
            }
            if (resp.statusCode() >= 500) {
                lastTransientError = new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
                logger.warn("TypeSafe call attempt {} of {} got HTTP {}, retrying", attempt, MAX_ATTEMPTS, resp.statusCode());
                continue;
            }
            if (resp.statusCode() < 200 || resp.statusCode() > 299) {
                throw new BotCommandException("TypeSafe request failed (HTTP " + resp.statusCode() + "): " + resp.body());
            }
            return parseAndValidate(resp.body(), request);
        }

        throw new BotCommandException("TypeSafe call failed after " + MAX_ATTEMPTS + " attempts: " +
                (lastTransientError == null ? "unknown error" : lastTransientError.getMessage()), lastTransientError);
    }

    /**
     * Parses a 2xx body and asserts every question we asked was answered. Without this, a
     * non-JSON or partial body yields a Response whose answers map is missing, and the failure
     * only surfaces much later as an opaque NullPointerException ("...failed: null").
     */
    private static TypeSafeTypes.Response parseAndValidate(String body, TypeSafeTypes.Request request) {
        TypeSafeTypes.Response response;
        try {
            response = GSON.fromJson(body, TypeSafeTypes.Response.class);
        } catch (Exception e) {
            throw new BotCommandException(
                    "TypeSafe returned a response that is not valid JSON; check the TypeSafe base URL is correct "
                            + "and that no proxy rewrote the response.", e);
        }
        for (String question : request.questions.keySet()) {
            if (response == null || response.answers == null || response.answers.get(question) == null) {
                throw new BotCommandException("TypeSafe returned a response missing the answer to question '"
                        + question + "'; check the question schema sent in the request.");
            }
        }
        return response;
    }
}
