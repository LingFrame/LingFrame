package com.lingframe.example.saas.api.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class SaasSeckillActive implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long skuId;
    private Integer stock;
    private Date startTime;
    private Date endTime;
}
