package como.begoml.stackedalphavideo

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform