package com.lingframe.example.saas.api.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class OAuthRenderResult implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String redirectUrl;
    private String info;
}
