package com.lingframe.core.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginStatusDTO {
    private String status;  // ACTIVE, LOADED, UNLOADED
}
