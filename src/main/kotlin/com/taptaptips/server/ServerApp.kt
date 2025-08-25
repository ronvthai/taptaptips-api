package com.taptaptips.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class ServerApp

fun main(args: Array<String>) {
    runApplication<ServerApp>(*args)
}
