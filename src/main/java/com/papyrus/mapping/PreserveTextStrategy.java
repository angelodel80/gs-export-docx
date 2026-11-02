package com.papyrus.mapping;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class PreserveTextStrategy implements TextConversionStrategy {
    // Legacy names are conservative aliases, never destructive normalizations.
    public Set<String> names() { return Set.of("PRESERVE", "FULL_TEXT", "CHAR_LEVEL"); }
    public String convert(String language, String content) { return content; }
}
