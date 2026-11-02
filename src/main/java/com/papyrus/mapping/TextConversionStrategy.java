package com.papyrus.mapping;

import java.util.Set;

/** An explicitly selected editorial policy. Implementations must not mutate input units. */
public interface TextConversionStrategy {
    Set<String> names();
    String convert(String language, String content);
}
