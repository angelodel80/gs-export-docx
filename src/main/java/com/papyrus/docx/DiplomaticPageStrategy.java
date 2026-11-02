package com.papyrus.docx;

import com.papyrus.domain.Edition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import static com.papyrus.mapping.GsUnitMappingStrategy.*;

@Component
@Order(10)
public class DiplomaticPageStrategy implements PageLayoutStrategy {
    private final WordStyles styles;
    public DiplomaticPageStrategy(WordStyles styles) { this.styles = styles; }

    public void render(XWPFDocument doc, Edition.Fragment fragment, int ordinal) {
        var table = styles.table(doc, 4150, 950, 4538);
        var row = table.getRow(0);
        styles.content(row.getCell(0), fragment.text(SOURCES), "LatinText");
        styles.paragraph(row.getCell(0), "", "LatinText");
        styles.content(row.getCell(0), fragment.text(PALEOGRAPHIC), "GreekText");
        styles.lineNumbers(row.getCell(1), fragment.text(DIPLOMATIC), styles.fragmentLabel(fragment));
        styles.content(row.getCell(2), fragment.text(DIPLOMATIC), "GreekText");
    }
}
