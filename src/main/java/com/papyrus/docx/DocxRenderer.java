package com.papyrus.docx;

import com.papyrus.domain.Edition;
import java.io.*;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

@Component
public class DocxRenderer implements DocumentRenderer {
    private final List<PageLayoutStrategy> pages;
    private final WordStyles styles;
    public DocxRenderer(List<PageLayoutStrategy> pages, WordStyles styles) {
        if (pages.isEmpty()) throw new IllegalStateException("No DOCX page strategies registered");
        this.pages = List.copyOf(pages);
        this.styles = styles;
    }
    public String format() { return "DOCX"; }
    public String mediaType() { return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
    public String extension() { return "docx"; }

    public byte[] render(Edition edition) throws IOException {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            styles.initialize(document);
            document.getProperties().getCoreProperties().setTitle(edition.title());
            document.getProperties().getCoreProperties().setCreator(edition.creator());
            int ordinal = 0;
            boolean first = true;
            for (var fragment : edition.fragments()) {
                ordinal++;
                for (var page : pages) {
                    if (!first) document.createParagraph().setPageBreak(true);
                    first = false;
                    page.render(document, fragment, ordinal);
                }
            }
            document.write(out);
            return out.toByteArray();
        }
    }
}
