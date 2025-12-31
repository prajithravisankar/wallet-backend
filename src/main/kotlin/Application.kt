package com.example

import io.ktor.server.application.*
import com.example.plugins.configureDatabases

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureHTTP() // this setsup cors and openAPI
    configureSerialization() // takes care of json serialization
    configureDatabases() // connects postgre sql and seeds demo data
    configureRouting() // sets up routing, registers all rest routes
}
