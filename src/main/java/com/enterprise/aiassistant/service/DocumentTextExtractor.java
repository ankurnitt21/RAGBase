package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.exception.DocumentIngestionException;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;
import technology.tabula.extractors.BasicExtractionAlgorithm;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Extracts plain text from various file formats, converting structured
 * content (tables) to Markdown table syntax so the LLM can understand them.
 *
 * Supported formats:
 *   .pdf  — PDFBox text + Tabula table detection (mixed text+table PDFs handled)
 *   .docx — Apache POI XWPF: preserves paragraph order, tables → Markdown
 *   .xlsx / .xls — Apache POI: each sheet becomes a Markdown table
 *   .csv  — OpenCSV: rows → Markdown table
 *   other — raw UTF-8 text
 */
@Component
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    /**
     * Extract text from the given stream, routing by file extension.
     * Returns a UTF-8 string; tables are formatted as Markdown.
     */
    public String extract(InputStream stream, String filename) throws IOException {
        String lower = filename != null ? filename.toLowerCase() : "";
        if (lower.endsWith(".pdf")) {
            return extractPdf(stream, filename);
        } else if (lower.endsWith(".docx")) {
            return extractDocx(stream, filename);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            return extractExcel(stream, filename);
        } else if (lower.endsWith(".csv")) {
            return extractCsv(stream, filename);
        }
        // Plain text fallback (.txt, .md, etc.)
        String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        log.info("Plain-text '{}': {} characters", filename, text.length());
        return text;
    }

    // ── PDF ─────────────────────────────────────────────────────────────────

    /**
     * Extracts text from a PDF that may contain both prose and tables.
     *
     * Strategy (per page):
     *  1. Use Tabula's SpreadsheetExtractionAlgorithm (bordered tables) first.
     *     If it finds nothing, fall back to BasicExtractionAlgorithm (whitespace tables).
     *  2. Build a list of "segments" in top-to-bottom page order:
     *       - Text regions above / between / below table bounding boxes → plain text
     *         (extracted with PDFTextStripperByArea so we skip the table cells)
     *       - Table regions → Markdown table
     *  3. Concatenate all segments across all pages.
     *
     * If no tables are found on any page the result equals a standard PDFBox extraction.
     */
    private String extractPdf(InputStream stream, String filename) throws IOException {
        byte[] bytes = stream.readAllBytes();
        if (bytes.length == 0) {
            throw new DocumentIngestionException("'" + filename + "' is empty.");
        }
        try (PDDocument pdfDoc = PDDocument.load(bytes)) {
            int pageCount = pdfDoc.getNumberOfPages();
            StringBuilder fullText = new StringBuilder();

            SpreadsheetExtractionAlgorithm spreadsheetAlgo = new SpreadsheetExtractionAlgorithm();
            BasicExtractionAlgorithm basicAlgo = new BasicExtractionAlgorithm();

            try (ObjectExtractor extractor = new ObjectExtractor(pdfDoc)) {
                for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
                    Page page = extractor.extract(pageNum);
                    float pageHeight = (float) page.getHeight();

                    // 1. Detect tables on this page
                    List<Table> tables = spreadsheetAlgo.extract(page);
                    if (tables.isEmpty()) {
                        tables = basicAlgo.extract(page);
                    }

                    if (tables.isEmpty()) {
                        // No tables on this page — plain PDFBox extraction
                        PDFTextStripper stripper = new PDFTextStripper();
                        stripper.setStartPage(pageNum);
                        stripper.setEndPage(pageNum);
                        fullText.append(stripper.getText(pdfDoc)).append("\n");
                    } else {
                        // Sort tables top-to-bottom by their Y position
                        tables.sort(Comparator.comparingDouble(t -> t.getY()));

                        PDFTextStripperByArea areaStripper = new PDFTextStripperByArea();
                        areaStripper.setSortByPosition(true);

                        // Register text regions: areas above/between/below tables
                        float cursor = 0f;
                        int regionIdx = 0;
                        List<String> regionNames = new ArrayList<>();
                        List<Table> sortedTables = tables;

                        for (Table table : sortedTables) {
                            float tableTop = (float) table.getY();
                            float tableBottom = tableTop + (float) table.getHeight();

                            // Text region before this table
                            if (tableTop > cursor + 2) {
                                String regionName = "text_" + regionIdx++;
                                areaStripper.addRegion(regionName,
                                        new Rectangle2D.Float(0, cursor, (float) page.getWidth(), tableTop - cursor));
                                regionNames.add(regionName);
                            }
                            // Placeholder to remember table position in output
                            regionNames.add("TABLE:" + sortedTables.indexOf(table));
                            cursor = tableBottom;
                        }
                        // Text region after the last table
                        if (cursor < pageHeight - 2) {
                            String regionName = "text_" + regionIdx;
                            areaStripper.addRegion(regionName,
                                    new Rectangle2D.Float(0, cursor, (float) page.getWidth(), pageHeight - cursor));
                            regionNames.add(regionName);
                        }

                        areaStripper.extractRegions(pdfDoc.getPage(pageNum - 1));

                        // Build output in reading order
                        for (String name : regionNames) {
                            if (name.startsWith("TABLE:")) {
                                int tIdx = Integer.parseInt(name.substring(6));
                                Table table = sortedTables.get(tIdx);
                                fullText.append("\n").append(tabulaTableToMarkdown(table)).append("\n");
                            } else {
                                String regionText = areaStripper.getTextForRegion(name);
                                if (regionText != null && !regionText.isBlank()) {
                                    fullText.append(regionText).append("\n");
                                }
                            }
                        }
                    }
                }
            }

            String result = fullText.toString();
            if (result.isBlank()) {
                throw new DocumentIngestionException(
                        "'" + filename + "' appears to be a scanned/image-based PDF. " +
                        "No text could be extracted. Please use a text-based PDF.");
            }
            log.info("PDF '{}': {} pages, {} chars (tables converted to Markdown)", filename, pageCount, result.length());
            return result;
        }
    }

    /** Converts a Tabula Table to a Markdown table string. */
    @SuppressWarnings("rawtypes")
    private String tabulaTableToMarkdown(Table table) {
        List<String[]> rows = new ArrayList<>();
        for (List<RectangularTextContainer> row : table.getRows()) {
            String[] cells = row.stream()
                    .map(cell -> cell.getText().replaceAll("\\s+", " ").trim())
                    .toArray(String[]::new);
            rows.add(cells);
        }
        return toMarkdownTable(rows);
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────

    private String extractDocx(InputStream stream, String filename) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(stream)) {
            for (IBodyElement elem : doc.getBodyElements()) {
                if (elem instanceof XWPFParagraph para) {
                    String text = para.getText();
                    if (!text.isBlank()) {
                        sb.append(text).append("\n");
                    }
                } else if (elem instanceof XWPFTable table) {
                    sb.append("\n").append(docxTableToMarkdown(table)).append("\n");
                }
            }
        }
        log.info("DOCX '{}': extracted {} characters", filename, sb.length());
        return sb.toString();
    }

    private String docxTableToMarkdown(XWPFTable table) {
        List<String[]> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            String[] cells = row.getTableCells().stream()
                    .map(cell -> cell.getText().replaceAll("\\s+", " ").trim())
                    .toArray(String[]::new);
            rows.add(cells);
        }
        return toMarkdownTable(rows);
    }

    // ── Excel ────────────────────────────────────────────────────────────────

    private String extractExcel(InputStream stream, String filename) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(stream)) {
            DataFormatter fmt = new DataFormatter();
            for (Sheet sheet : wb) {
                sb.append("## ").append(sheet.getSheetName()).append("\n\n");
                List<String[]> rows = new ArrayList<>();
                for (Row row : sheet) {
                    int lastCol = row.getLastCellNum();
                    if (lastCol < 0) continue;
                    String[] cells = new String[lastCol];
                    for (int c = 0; c < lastCol; c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        cells[c] = fmt.formatCellValue(cell).trim();
                    }
                    rows.add(cells);
                }
                sb.append(toMarkdownTable(rows)).append("\n");
            }
        }
        log.info("Excel '{}': extracted {} characters", filename, sb.length());
        return sb.toString();
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    private String extractCsv(InputStream stream, String filename) throws IOException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            List<String[]> rows = reader.readAll();
            String result = toMarkdownTable(rows);
            log.info("CSV '{}': {} rows, {} characters", filename, rows.size(), result.length());
            return result;
        } catch (CsvException e) {
            throw new IOException("Failed to parse CSV '" + filename + "': " + e.getMessage(), e);
        }
    }

    // ── Shared table renderer ─────────────────────────────────────────────────

    /**
     * Converts a list of String arrays (rows) into a Markdown table.
     * The first row is treated as the header; a separator line is inserted after it.
     * Pipe characters inside cell values are escaped.
     */
    public static String toMarkdownTable(List<String[]> rows) {
        if (rows.isEmpty()) return "";
        int cols = rows.stream().mapToInt(r -> r.length).max().orElse(0);
        if (cols == 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            sb.append("| ");
            for (int c = 0; c < cols; c++) {
                String val = (c < row.length) ? row[c] : "";
                sb.append(val.replace("|", "\\|")).append(" | ");
            }
            sb.append("\n");
            if (r == 0) {
                // Header separator
                sb.append("|");
                for (int c = 0; c < cols; c++) sb.append(" --- |");
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
