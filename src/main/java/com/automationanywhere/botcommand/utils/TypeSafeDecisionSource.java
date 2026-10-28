package com.automationanywhere.botcommand.utils;

/** Abstraction over "ask TypeSafe (or a test double) for a classification". */
public interface TypeSafeDecisionSource {
    TypeSafeTypes.Response classify(TypeSafeTypes.Request request);
}
