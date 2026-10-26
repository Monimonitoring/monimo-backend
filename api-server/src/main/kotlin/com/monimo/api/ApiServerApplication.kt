package com.monimo.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// API server
@SpringBootApplication
class ApiServerApplication

fun main(args: Array<String>) {
    runApplication<ApiServerApplication>(*args)
}
