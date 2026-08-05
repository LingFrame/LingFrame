package com.lingframe.example.mall.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResponseResult<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer code;
    private String msg;
    private T data;

    public static <T> ResponseResult<T> success() {
        return new ResponseResult<>(200, "success", null);
    }

    public static <T> ResponseResult<T> success(T data) {
        return new ResponseResult<>(200, "success", data);
    }

    public static <T> ResponseResult<T> fail(String msg) {
        return new ResponseResult<>(500, msg, null);
    }

    public static <T> ResponseResult<T> fail(Integer code, String msg) {
        return new ResponseResult<>(code, msg, null);
    }
}
