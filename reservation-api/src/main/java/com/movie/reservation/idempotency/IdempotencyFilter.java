package com.movie.reservation.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.reservation.dto.ErrorResponse;
import com.movie.reservation.exception.ReservationApiException;
import com.movie.reservation.exception.ReservationErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Pattern TICKET_PATH = Pattern.compile("^/api/v1/screenings/([^/]+)/tickets/?$");

    private final IdempotencyFingerprint fingerprint;
    private final IdempotencyValidator validator;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Matcher matcher = TICKET_PATH.matcher(request.getRequestURI());
        if (!"POST".equals(request.getMethod()) || !matcher.matches()) {
            filterChain.doFilter(request, response);
            return;
        }

        String screeningId = matcher.group(1);
        IdempotencyRequestWrapper wrapped = new IdempotencyRequestWrapper(request);
        try {
            String idempotencyKey = request.getHeader("Idempotency-Key");
            String queueToken = request.getHeader("Queue-Token");
            validator.requireValidKey(screeningId, idempotencyKey);
            String requestFingerprint = fingerprint.build(wrapped, queueToken == null ? "" : queueToken, wrapped.body());
            validator.validateAndClaim(screeningId, idempotencyKey, requestFingerprint);
            filterChain.doFilter(wrapped, response);
        } catch (ReservationApiException exception) {
            writeError(response, exception);
        } catch (IllegalArgumentException exception) {
            writeError(response, new ReservationApiException(ReservationErrorCode.INVALID_TICKET_QUANTITY, screeningId, null));
        }
    }

    private void writeError(HttpServletResponse response, ReservationApiException exception) throws IOException {
        ReservationErrorCode code = exception.getErrorCode();
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
                code.name(),
                code.message(),
                code.retryable(),
                exception.getScreeningId(),
                exception.getIdempotencyKey()
        ));
    }
}
