package com.papyrus.docx;

import com.papyrus.domain.Edition;
import java.math.BigInteger;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import static com.papyrus.mapping.GsUnitMappingStrategy.*;

@Component
@Order(20)
public class LiteraryPageStrategy implements PageLayoutStrategy {
    private final WordStyles styles;
    public LiteraryPageStrategy(WordStyles styles) { this.styles = styles; }

    public void render(XWPFDocument doc, Edition.Fragment fragment, int ordinal) {
        var table = styles.table(doc, 800, 4419, 4419);
        var row = table.getRow(0);
        styles.lineNumbers(row.getCell(0), fragment.text(LITERARY), styles.fragmentLabel(fragment));
        styles.content(row.getCell(1), fragment.text(LITERARY), "GreekText");
        styles.content(row.getCell(2), fragment.text(TRANSLATION), "LatinText");
        var apparatus = table.createRow();
        var cell = apparatus.getCell(0);
        var pr = cell.getCTTc().getTcPr();
        if (pr == null) pr = cell.getCTTc().addNewTcPr();
        pr.addNewGridSpan().setVal(BigInteger.valueOf(3));
        cell.setWidth(Integer.toString(WordStyles.WIDTH));
        apparatus.removeCell(2);
        apparatus.removeCell(1);
        styles.content(cell, fragment.text(PHILOLOGICAL), "GreekText");
    }
}
