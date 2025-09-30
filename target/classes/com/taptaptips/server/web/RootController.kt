package com.taptaptips.server.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class RootController {
    @GetMapping("/healthz")
    fun healthz() = mapOf("ok" to true)
}
