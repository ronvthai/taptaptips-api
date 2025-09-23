package com.taptaptips.server.web

import org.slf4j.LoggerFactory
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
}
