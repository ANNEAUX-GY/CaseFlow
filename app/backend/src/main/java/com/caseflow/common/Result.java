package com.caseflow.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体：{code, msg, data}
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int SUCCESS = 0;
    public static final int ERROR = 500;

    private int code;
    private String msg;
    private T data;

    public Result() {
    }

    public Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> Result<T> ok() {
        return new Result<>(SUCCESS, "ok", null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS, "ok", data);
    }

    public static <T> Result<T> fail(String msg) {
        return new Result<>(ERROR, msg, null);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    public boolean success() {
        return SUCCESS == code;
    }
}
