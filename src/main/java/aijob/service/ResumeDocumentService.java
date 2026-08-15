package aijob.service;

import aijob.dto.ResumeDocumentNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.nio.file.Path;
import java.util.List;

public interface ResumeDocumentService {

    List<ResumeDocumentNode> parseDocx(Path path);

    XWPFParagraph findParagraph(XWPFDocument document, String nodeId);
}
