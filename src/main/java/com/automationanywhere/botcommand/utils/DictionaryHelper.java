package com.automationanywhere.botcommand.utils;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;

import java.util.LinkedHashMap;

/**
 * Shared output envelope for the package's simple (non-FSD) actions, per
 * AA_PACKAGE_BEST_PRACTICES.md §5.2: every action returns the same field set
 * so a bot author never has to guess what's available from one action to the
 * next. {@code result} carries whatever single piece of action-specific data
 * exists (a page title, an extracted value, a mode that was chosen); actions
 * with nothing meaningful to report leave it empty.
 */
public final class DictionaryHelper {

    private DictionaryHelper() {
    }

    public static DictionaryValue success(String result) {
        return success(result, 0);
    }

    public static DictionaryValue success(String result, long elapsedMs) {
        return envelope("success", result, elapsedMs, "");
    }

    public static DictionaryValue error(String errorMessage) {
        return error(errorMessage, 0);
    }

    public static DictionaryValue error(String errorMessage, long elapsedMs) {
        return envelope("error", "", elapsedMs, errorMessage);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DictionaryValue envelope(String status, String result, long elapsedMs, String errorMessage) {
        LinkedHashMap<String, Value> fields = new LinkedHashMap<>();
        fields.put("result", new StringValue(result == null ? "" : result));
        fields.put("status", new StringValue(status));
        fields.put("elapsed_ms", new StringValue(String.valueOf(elapsedMs)));
        fields.put("error_message", new StringValue(errorMessage == null ? "" : errorMessage));
        return new DictionaryValue(fields);
    }
}
