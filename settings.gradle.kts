rootProject.name = "happymh-tachij2k"

include(":core")
include(":extensions:individual:zh:happymh")

project(":extensions:individual:zh:happymh").projectDir = file("src/zh/happymh")
include(":extensions:individual:zh:baozimhorg")

project(":extensions:individual:zh:baozimhorg").projectDir = file("src/zh/baozimhorg")
