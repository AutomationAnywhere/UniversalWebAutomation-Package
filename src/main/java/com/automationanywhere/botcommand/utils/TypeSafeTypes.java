package com.automationanywhere.botcommand.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Java models for the TypeSafe `POST /v1/systemone` request/response shape. */
public class TypeSafeTypes {

    public static class ChoiceQuestion {
        public String type = "choice";
        public String instructions;
        public Map<String, String> criteria = new LinkedHashMap<>();
    }

    public static class Request {
        public String state;
        public String model = "jev-latest";
        public Map<String, ChoiceQuestion> questions = new LinkedHashMap<>();
    }

    public static class ChoiceAnswer {
        public String type;
        public String choice;
        public Map<String, Double> probabilities;
        public double confidence;
    }

    public static class Usage {
        public long input_tokens;
        public long output_tokens;
    }

    public static class Response {
        public String model;
        public Map<String, ChoiceAnswer> answers;
        public Usage usage;
    }
}
