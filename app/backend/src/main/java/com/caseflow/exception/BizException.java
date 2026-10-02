package com.caseflow.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务异常：直接把提示语透给前端。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class BizException extends RuntimeException {

    private int code = 500;

    public BizException(String message) {
        super(message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
