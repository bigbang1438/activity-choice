package local.activity;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.dao.DataAccessException;
import java.util.Map;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiError.class) ResponseEntity<?> business(ApiError e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<?> badInput(Exception e){return ResponseEntity.badRequest().body(Map.of("code","invalid_input","message","请求格式或字段类型无效"));}
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> missing(Exception e){return ResponseEntity.status(404).body(Map.of("code","not_found","message","接口或页面不存在"));}
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception e){return ResponseEntity.status(405).body(Map.of("code","method_not_allowed","message","请求方式不支持"));}
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> data(Exception e){return ResponseEntity.status(409).body(Map.of("code","conflict","message","数据已变化或正忙，请刷新后重试"));}
}
