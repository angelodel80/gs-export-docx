package com.papyrus.api;

import com.papyrus.application.DocumentProcessorService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/papyrus")
public class PapyrusController {
    private final DocumentProcessorService service;
    public PapyrusController(DocumentProcessorService service) { this.service = service; }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "GS_UNIT") String mapping,
            @RequestParam(defaultValue = "PRESERVE") String granularity,
            @RequestParam(defaultValue = "DOCX") String format,
            @RequestParam(defaultValue = "false") boolean archive) throws IOException {
        try (var input = file.getInputStream()) {
            var result = service.convert(input, mapping, granularity, format, archive);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(result.mediaType()))
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(result.filename(), StandardCharsets.UTF_8).build().toString())
                    .header("X-Archive-Status", result.archiveStatus().name())
                    .header("X-Fragment-Count", Integer.toString(result.fragments()))
                    .body(result.bytes());
        }
    }
}
