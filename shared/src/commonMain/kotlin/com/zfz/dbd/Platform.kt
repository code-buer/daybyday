package com.zfz.dbd

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform