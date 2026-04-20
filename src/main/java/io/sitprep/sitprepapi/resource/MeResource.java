package io.sitprep.sitprepapi.resource;

import io.sitprep.sitprepapi.dto.MeDto;
import io.sitprep.sitprepapi.service.MeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
@CrossOrigin(origins = "http://localhost:3000")
public class MeResource {

    private final MeService meService;

    public MeResource(MeService meService) {
        this.meService = meService;
    }

    @GetMapping("/{uid}")
    public ResponseEntity<MeDto> getMe(@PathVariable String uid) {
        return meService.buildMe(uid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
