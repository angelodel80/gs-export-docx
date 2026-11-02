package com.papyrus.docx;

import com.papyrus.domain.Edition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Spring @Order determines the page sequence. Each strategy starts its own page. */
public interface PageLayoutStrategy {
    void render(XWPFDocument document, Edition.Fragment fragment, int ordinal);
}
