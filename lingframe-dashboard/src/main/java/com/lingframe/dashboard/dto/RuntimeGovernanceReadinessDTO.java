package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeGovernanceReadinessDTO {
    private String status;
    private String summary;
    private boolean sharedApiBoundaryFrozen;
    private int diagnosticsCount;

    @Builder.Default
    private List<String> blockers = new ArrayList<>();

    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
