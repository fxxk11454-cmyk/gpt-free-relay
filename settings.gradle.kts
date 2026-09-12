pluginManagement {
    repositories {
        // 走梯子后 dl.google.com / repo1.maven.org 会挂住，
        // 这里全部改用阿里云 Maven 镜像（实测秒回）。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "GptRelay"
include(":app")
