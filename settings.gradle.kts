pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
rootProject.name = "Stocks_Dictionary"
include(":host")
include(":StockLevels_app")
project(":StockLevels_app").projectDir = file("modules/StockLevels_app")
include(":WordSaver_app")
project(":WordSaver_app").projectDir = file("modules/WordSaver_app")
include(":TradingHub_project_app")
project(":TradingHub_project_app").projectDir = file("modules/TradingHub_project_app")
include(":StockScope_app")
project(":StockScope_app").projectDir = file("modules/StockScope_app")
