plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MKissa"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    listOf("en").forEach {
        source {
            lang = it
            baseUrl = "https://mkissa.to"
        }
    }

    deeplink {
        host("mkissa.to")
        path("/manga/..*/")
    }
}

dependencies {

}