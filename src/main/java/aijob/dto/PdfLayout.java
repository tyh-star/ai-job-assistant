package aijob.dto;

import aijob.enums.ResumeExtractionStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PdfLayout {

    private String status;
    private float pageWidth;
    private float pageHeight;
    private List<ResumeTextBlock> blocks;
    private List<HorizontalRule> horizontalRules;

    public static PdfLayout imageOnly() {
        PdfLayout layout = new PdfLayout();
        layout.setStatus(ResumeExtractionStatus.IMAGE_ONLY.name());
        layout.setBlocks(new ArrayList<>());
        return layout;
    }

    public static PdfLayout ofBlocks(float pageWidth, float pageHeight, List<ResumeTextBlock> blocks) {
        return ofBlocks(pageWidth, pageHeight, blocks, null);
    }

    public static PdfLayout ofBlocks(
            float pageWidth,
            float pageHeight,
            List<ResumeTextBlock> blocks,
            List<HorizontalRule> horizontalRules) {
        PdfLayout layout = new PdfLayout();
        layout.setPageWidth(pageWidth);
        layout.setPageHeight(pageHeight);
        layout.setBlocks(blocks == null ? new ArrayList<>() : blocks);
        layout.setHorizontalRules(horizontalRules == null ? new ArrayList<>() : horizontalRules);
        layout.setStatus(layout.getBlocks().isEmpty()
                ? ResumeExtractionStatus.IMAGE_ONLY.name()
                : ResumeExtractionStatus.LAYOUT.name());
        return layout;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HorizontalRule {
        private int page;
        private float x;
        private float y;
        private float width;
    }
}
