package com.spintowin.common.fixture;

import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.openapi.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fixture")
class FixtureController {

    record PrizeRequest(@NotBlank String name, @Min(0) int stock) {
    }

    @PostMapping("/prizes")
    PrizeRequest create(@Valid @RequestBody PrizeRequest request) {
        return request;
    }

    @GetMapping("/draws")
    @ApiErrors({ErrorCode.CAMPAIGN_NOT_FOUND, ErrorCode.VALIDATION_FAILED})
    int draws(@RequestParam @Min(1) int count) {
        return count;
    }

    @GetMapping("/boom")
    String boom() {
        throw new IllegalStateException("db password is hunter2");
    }
}
