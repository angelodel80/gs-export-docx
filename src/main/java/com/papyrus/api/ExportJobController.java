package com.papyrus.api;

import com.papyrus.jobs.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.InputStreamResource;

@RestController
@RequestMapping("/api/papyrus/jobs")
public class ExportJobController {
    private final ExportJobService jobs;
    public ExportJobController(ExportJobService jobs) { this.jobs = jobs; }
    @GetMapping("/config") public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.capabilities());
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobSnapshot> submit(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "DOWNLOAD") String delivery) throws IOException {
        var job = jobs.submit(file, delivery);
        return ResponseEntity.accepted().location(URI.create("/api/papyrus/jobs/" + job.id()))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.RETRY_AFTER, "2").body(job);
    }
    @GetMapping("/{id}") public ResponseEntity<JobSnapshot> status(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.status(id));
    }
    @GetMapping("/{id}/document")
    public ResponseEntity<InputStreamResource> document(@PathVariable UUID id) throws IOException {
        var job = jobs.status(id);
        // Open now so an expired/not-ready job produces a normal HTTP problem, not a partial 200.
        InputStream input = jobs.openDocument(id);
        var stream = new InputStreamResource(input);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(job.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(job.filename(), StandardCharsets.UTF_8).build().toString())
                .body(stream);
    }
}
