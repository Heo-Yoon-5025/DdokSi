package com.ddoksi.ddoksi.common.web;

import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * API 예외를 한 곳에서 응답으로 변환한다.
 *
 * <p>컨트롤러마다 try-catch 를 흩어놓지 않기 위한 것이고, 무엇보다
 * <b>내부 예외 메시지나 스택트레이스를 클라이언트에 흘리지 않기 위한</b> 장치다.
 *
 * <p>{@link ResponseEntityExceptionHandler} 를 상속하는 이유:
 * Spring MVC 가 이미 표준 예외들을 적절한 상태 코드로 매핑해 둔다
 * (파라미터 타입 불일치 400, 지원하지 않는 메서드 405, 잘못된 본문 400 등).
 * 이를 상속하지 않고 {@code Exception} 만 catch-all 로 잡으면 그 매핑을 전부 500 으로 덮어쓴다.
 * 실제로 {@code status=NOPE} 가 500 으로 응답되는 문제가 그래서 생겼다.
 *
 * <p>응답 형식은 RFC 9457 ProblemDetail 을 쓴다. Spring 이 기본 제공하므로
 * 자체 오류 포맷을 새로 만들 이유가 없다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 조회 대상이 없는 경우. 잘못된 요청이지 서버 장애가 아니다. */
    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("대상을 찾을 수 없습니다");
        problem.setDetail(e.getMessage());
        return problem;
    }

    /**
     * 파라미터 값이 해당 타입으로 변환되지 않는 경우 (예: status=NOPE, id=abc).
     *
     * 어떤 파라미터가 문제인지는 알려주되, 허용 값 목록은 노출하지 않는다.
     * 열거형 상수 전체를 응답에 담으면 내부 모델이 그대로 드러난다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.debug("파라미터 타입 불일치: name={}, value={}", e.getName(), e.getValue());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("요청 형식이 올바르지 않습니다");
        problem.setDetail("파라미터 '" + e.getName() + "' 의 값이 올바르지 않습니다.");
        return problem;
    }

    /** 비즈니스 규칙 위반. 재시도해도 결과가 같으므로 400 으로 알린다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        log.debug("잘못된 요청: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("요청 형식이 올바르지 않습니다");
        return problem;
    }

    /**
     * 예상하지 못한 오류.
     *
     * 원인은 서버 로그에만 남기고 클라이언트에는 일반적인 메시지만 준다.
     * 예외 메시지에 SQL 이나 내부 경로가 섞여 나갈 수 있다.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("서버 오류가 발생했습니다");
        return problem;
    }
}
