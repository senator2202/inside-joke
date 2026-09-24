package com.insidejoke.ai;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VoiceLineController {

    private final VoiceLineCacheService audio;

    public VoiceLineController(VoiceLineCacheService audio) {
        this.audio = audio;
    }

    @GetMapping("/api/voice-lines/{id}")
    public ResponseEntity<byte[]> clip(@PathVariable String id) {
        byte[] mp3 = audio.get(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "This clip has expired."));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .body(mp3);
    }
}
