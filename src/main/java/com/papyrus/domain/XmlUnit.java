package com.papyrus.domain;

import java.util.List;

/** Content is the XML character data, including spaces and line breaks. */
public record XmlUnit(String id, String title, String creator, String target,
        List<String> children, String language, String content, byte[] originalXml) {
    public XmlUnit {
        children = List.copyOf(children);
        originalXml = originalXml.clone();
    }
    @Override public byte[] originalXml() { return originalXml.clone(); }
}
