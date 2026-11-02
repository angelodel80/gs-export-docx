package com.papyrus.docx;

import com.papyrus.config.ExportProperties;
import com.papyrus.domain.Edition;
import java.math.BigInteger;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

@Component
public class WordStyles {
    public static final int WIDTH = 9638; // A4 minus two 20 mm margins, in twips
    private final ExportProperties config;

    public WordStyles(ExportProperties config) { this.config = config; }

    public void initialize(XWPFDocument doc) {
        var sect = doc.getDocument().getBody().addNewSectPr();
        var size = sect.addNewPgSz();
        size.setW(BigInteger.valueOf(11906));
        size.setH(BigInteger.valueOf(16838));
        var margins = sect.addNewPgMar();
        margins.setTop(BigInteger.valueOf(1134));
        margins.setBottom(BigInteger.valueOf(1134));
        margins.setLeft(BigInteger.valueOf(1134));
        margins.setRight(BigInteger.valueOf(1134));
        margins.setHeader(BigInteger.valueOf(708));
        margins.setFooter(BigInteger.valueOf(708));
        margins.setGutter(BigInteger.ZERO);
        XWPFStyles styles = doc.createStyles();
        addStyle(styles, "GreekText", config.greekFont(), 20);
        addStyle(styles, "LatinText", config.latinFont(), 20);
        addStyle(styles, "LineNumber", config.latinFont(), 16);
        var footer = doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
        var p = footer.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.getCTP().addNewFldSimple().setInstr("PAGE");
    }

    private void addStyle(XWPFStyles styles, String id, String font, int halfPoints) {
        CTStyle ct = CTStyle.Factory.newInstance();
        ct.setStyleId(id);
        ct.setType(STStyleType.PARAGRAPH);
        ct.addNewName().setVal(id);
        var run = ct.addNewRPr();
        var fonts = run.addNewRFonts();
        fonts.setAscii(font); fonts.setHAnsi(font); fonts.setCs(font); fonts.setEastAsia(font);
        run.addNewSz().setVal(BigInteger.valueOf(halfPoints));
        var spacing = ct.addNewPPr().addNewSpacing();
        spacing.setAfter(BigInteger.ZERO); spacing.setBefore(BigInteger.ZERO);
        styles.addStyle(new XWPFStyle(ct));
    }

    public XWPFTable table(XWPFDocument doc, int... widths) {
        XWPFTable table = doc.createTable(1, widths.length);
        table.setWidth(WIDTH);
        var pr = table.getCTTbl().getTblPr();
        var layout = pr.isSetTblLayout() ? pr.getTblLayout() : pr.addNewTblLayout();
        layout.setType(STTblLayoutType.FIXED);
        var grid = table.getCTTbl().getTblGrid();
        if (grid == null) grid = table.getCTTbl().addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int i = 0; i < widths.length; i++) {
            grid.addNewGridCol().setW(BigInteger.valueOf(widths[i]));
            table.getRow(0).getCell(i).setWidth(Integer.toString(widths[i]));
            table.getRow(0).getCell(i).setVerticalAlignment(XWPFTableCell.XWPFVertAlign.TOP);
        }
        table.setCellMargins(50, 70, 50, 70);
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "888888");
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "888888");
        return table;
    }

    public void content(XWPFTableCell cell, String text, String style) {
        for (String line : text.split("\n", -1)) paragraph(cell, line, style);
    }

    public void paragraph(XWPFTableCell cell, String text, String style) {
        XWPFParagraph p;
        if (cell.getParagraphs().size() == 1 && cell.getParagraphs().get(0).getRuns().isEmpty()
                && cell.getParagraphs().get(0).getStyle() == null) p = cell.getParagraphs().get(0);
        else p = cell.addParagraph();
        p.setStyle(style);
        p.setSpacingAfter(0); p.setSpacingBefore(0);
        p.setSpacingBetween(12, LineSpacingRule.EXACT); // same baseline for marginal numbers and text
        if (style.equals("LineNumber")) p.setAlignment(ParagraphAlignment.RIGHT);
        var run = p.createRun();
        run.setFontFamily(style.equals("GreekText") ? config.greekFont() : config.latinFont());
        run.setFontSize(style.equals("LineNumber") ? 8 : 10);
        // Word needs explicit tab elements; ordinary spaces are written with xml:space="preserve" by POI.
        String[] parts = text.split("\t", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) run.addTab();
            run.setText(parts[i]);
        }
    }

    public String fragmentLabel(Edition.Fragment fragment) {
        var match = Pattern.compile("(?i)\\b(Fr\\.\\s*\\d+|col\\.\\s*[IVXLCDM\\d]+)\\b").matcher(fragment.title());
        return match.find() ? match.group(1) : "";
    }

    public void lineNumbers(XWPFTableCell cell, String text, String fragmentLabel) {
        String[] lines = text.split("\n", -1);
        int total = 0;
        for (String line : lines) if (numbered(line)) total++;
        int n = 0;
        boolean labelUsed = false;
        for (String line : lines) {
            String label = "";
            if (numbered(line)) {
                n++;
                if (n % 5 == 0 || n == total) label = Integer.toString(n);
            } else if (!labelUsed && isDesunt(line)) {
                label = fragmentLabel;
                labelUsed = true;
            }
            paragraph(cell, label, "LineNumber");
        }
    }

    private boolean numbered(String line) {
        return !line.isBlank() && !isDesunt(line);
    }

    private boolean isDesunt(String line) { return line.stripLeading().matches("(?i)^desunt\\b.*"); }
}
