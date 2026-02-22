package com.lingframe.example.user.canary.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户 DTO - 金丝雀版本
 * 
 * 相比稳定版增加了额外字段用于实验性功能
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    private String id;
    private String name;
    private String email;
    
    // ========== 金丝雀版本新增字段 ==========
    
    /**
     * 用户状态 (实验性功能)
     */
    private String status;
    
    /**
     * 最后活跃时间 (实验性功能)
     */
    private String lastActiveAt;

    /**
     * 兼容构造器
     */
    public UserDTO(String id, String name, String email) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.status = "ACTIVE";
    }
}
