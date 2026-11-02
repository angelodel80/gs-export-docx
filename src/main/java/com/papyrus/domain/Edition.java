package com.papyrus.domain;

import java.util.List;
import java.util.Map;

public record Edition(String id, String title, String creator, List<Fragment> fragments) {
    public Edition { fragments = List.copyOf(fragments); }

    public record Fragment(String id, String title, Map<String, String> texts) {
        public Fragment { texts = Map.copyOf(texts); }
        public String text(String language) { return texts.getOrDefault(language, ""); }
    }
}
