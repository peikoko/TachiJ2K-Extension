rootProject.name = "happymh-tachij2k"

include(":core")
include(":extensions:individual:zh:happymh")

project(":extensions:individual:zh:happymh").projectDir = file("src/zh/happymh")
include(":extensions:individual:zh:baozimhorg")

project(":extensions:individual:zh:baozimhorg").projectDir = file("src/zh/baozimhorg")
include(":extensions:individual:zh:manwa")

project(":extensions:individual:zh:manwa").projectDir = file("src/zh/manwa")
include(":extensions:individual:zh:mh1234")

project(":extensions:individual:zh:mh1234").projectDir = file("src/zh/mh1234")
