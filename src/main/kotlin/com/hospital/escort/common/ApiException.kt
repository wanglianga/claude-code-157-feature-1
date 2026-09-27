package com.hospital.escort.common

import org.springframework.http.HttpStatus

/** 业务异常, 由 GlobalExceptionHandler 统一转成 HTTP 响应 */
class ApiException(
    val status: HttpStatus = HttpStatus.BAD_REQUEST,
    message: String
) : RuntimeException(message)

fun notFound(message: String): Nothing = throw ApiException(HttpStatus.NOT_FOUND, message)
fun conflict(message: String): Nothing = throw ApiException(HttpStatus.CONFLICT, message)
fun badRequest(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, message)
fun forbidden(message: String): Nothing = throw ApiException(HttpStatus.FORBIDDEN, message)
