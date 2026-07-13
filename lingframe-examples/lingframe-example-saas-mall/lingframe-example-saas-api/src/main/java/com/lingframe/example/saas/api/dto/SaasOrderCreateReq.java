package com.lingframe.example.saas.api.dto;

import lombok.Data;
import java.util.List;

@Data
public class SaasOrderCreateReq {
    private String receiverName;
    private String receiverPhone;
    private String receiverAddress;
    private List<SaasOrderItemReq> items;
}
