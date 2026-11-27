package cn.edu.lab3.delivery;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class Errors {
  private final Audit audit;

  Errors(Audit audit) {
    this.audit = audit;
  }

  @ExceptionHandler(Problem.class)
  ResponseEntity<Map<String, Object>> problem(Problem error, HttpServletRequest req) {
    audit.record((Actor) req.getAttribute("actor"), req.getRequestURI(),
        (Long) req.getAttribute("auditOrder"), (Long) req.getAttribute("auditShop"),
        req.getMethod() + " " + req.getRequestURI(), "", "",
        error.status == 403 ? "DENIED" : "FAILED", error.getMessage(),
        (String) req.getAttribute("auditRequest"));
    var body = new LinkedHashMap<>(error.details);
    body.put("message", error.getMessage());
    return ResponseEntity.status(error.status).body(body);
  }

  @ExceptionHandler({HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class})
  ResponseEntity<Map<String, Object>> invalid(Exception error, HttpServletRequest req) {
    return problem(new Problem(400, "字段格式无效，请检查输入"), req);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<Map<String, Object>> duplicate(DuplicateKeyException error,
      HttpServletRequest req) {
    return problem(Problem.conflict("记录已存在或发生并发修改，请刷新"), req);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, Object>> database(DataAccessException error, HttpServletRequest req) {
    return problem(new Problem(503, "数据库操作未确认，请按原请求号查询或重试"), req);
  }
}
