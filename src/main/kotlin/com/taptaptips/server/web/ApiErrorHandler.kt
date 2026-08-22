package com.taptaptips.server.web

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class ApiErrorHandler {
    private val log = LoggerFactory.getLogger(ApiErrorHandler::class.java)
    data class ApiError(val status: Int, val error: String, val message: String?)

    @ExceptionHandler(ResponseStatusException::class)
    fun handle(rse: ResponseStatusException, req: WebRequest): ResponseEntity<ApiError> {
        val code = rse.statusCode
        val errorText = (code as? HttpStatus)?.name ?: code.toString()
        // This is where the server-side "error log goes"
        log.warn("API error {} {} — {}", code.value(), errorText, rse.reason)

        return ResponseEntity
            .status(code)
            .body(ApiError(code.value(), errorText, rse.reason))
    }

    /**
     * Catches unique-constraint / FK races that slip past application-level
     * checks — e.g. two concurrent requests both passing a "does this
     * already exist?" check before either has committed. The FCM token
     * registration race (idx_fcm_token) is the known example, fixed at the
     * source with an atomic upsert in FcmTokenRepository — this handler is
     * the safety net for that same shape anywhere else in the app, so an
     * unhardened race returns 409 Conflict instead of a raw 500 stack trace.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleConflict(e: DataIntegrityViolationException, req: WebRequest): ResponseEntity<ApiError> {
        log.warn("Data integrity conflict: {}", e.mostSpecificCause.message)
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ApiError(
                HttpStatus.CONFLICT.value(),
                "CONFLICT",
                "That request conflicts with existing data — it may have already been processed."
            ))
    }
}
