package com.lingframe.core.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourcePermissionDTO {
    private boolean dbRead;
    private boolean dbWrite;
    private boolean cacheRead;
    private boolean cacheWrite;
}
