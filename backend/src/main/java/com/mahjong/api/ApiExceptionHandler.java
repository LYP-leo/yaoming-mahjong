package com.mahjong.api;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String,Object> notFound(Exception e){return error("NOT_FOUND",e.getMessage());}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,Object> badRequest(Exception e){return error("INVALID_ACTION",e.getMessage());}
    @ExceptionHandler(MethodArgumentNotValidException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,Object> validation(MethodArgumentNotValidException e){return error("VALIDATION_ERROR",e.getBindingResult().getFieldErrors().stream().findFirst().map(f->friendlyValidation(f.getField(),f.getDefaultMessage())).orElse("请求参数错误"));}
    private String friendlyValidation(String field,String detail){
        String name=Map.of("name","牌局名称","playerName","昵称","playerId","玩家身份","token","恢复码","requestId","请求标识","type","操作").getOrDefault(field,field);
        return name+" "+detail;
    }
    private Map<String,Object> error(String code,String message){return Map.of("timestamp",Instant.now().toString(),"code",code,"message",message);}
}
