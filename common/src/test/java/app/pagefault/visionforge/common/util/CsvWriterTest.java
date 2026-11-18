package app.pagefault.visionforge.common.util;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void writesUtf8BomHeadersAndEscapesSpecialCharactersAndFormulas() {
        CsvWriter writer = new CsvWriter()
                .addHeader("id", "formula", "quoted", "amount", "ok")
                .addTextCell("row-1")
                .addTextCell("=SUM(A1:A2)")
                .addTextCell("hello, \"world\"\nline2")
                .addNumberCell(-250)
                .addBooleanCell(true)
                .endRow()
                .addTextCell("+cmd|'/C calc'")
                .addTextCell("@HYPERLINK()")
                .addTextCell("-1+1")
                .addNumberCell(null)
                .addBooleanCell(false)
                .endRow();

        String csv = new String(writer.toUtf8Bytes(), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("\uFEFFid,formula,quoted,amount,ok\r\n");
        assertThat(csv).contains("row-1,'=SUM(A1:A2),\"hello, \"\"world\"\"\nline2\",-250,true\r\n");
        assertThat(csv).contains("'+cmd|'/C calc','@HYPERLINK(),'-1+1,,false\r\n");
    }
}
