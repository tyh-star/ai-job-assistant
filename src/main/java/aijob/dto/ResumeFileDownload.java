package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeFileDownload {

    private Path path;
    private String fileName;
    private String fileType;
}
