cmake_minimum_required(VERSION 3.21)

project(NeriPlayerQt VERSION 2.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_INCLUDE_CURRENT_DIR ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Quick Qml Network Svg DBus)

qt_standard_project_setup()

set(NERIPLAYER_SOURCES
    src/main.cpp
    src/backendclient.h
    src/backendclient.cpp
    src/systemtheme.h
    src/systemtheme.cpp
    src/singleinstance.h
    src/singleinstance.cpp
)

qt_add_executable(neriplayer ${NERIPLAYER_SOURCES})

target_link_libraries(neriplayer PRIVATE
    Qt6::Core
    Qt6::Gui
    Qt6::Quick
    Qt6::Qml
    Qt6::Network
    Qt6::Svg
    Qt6::DBus
)

target_compile_definitions(neriplayer PRIVATE
    NERIPLAYER_VERSION="${PROJECT_VERSION}"
    NERIPLAYER_SOURCE_QML_DIR="${CMAKE_CURRENT_SOURCE_DIR}/qml"
)

# 开发时 QML 直接从源码目录加载（改 QML 不用重新编译），打包时安装到 share/neriplayer/qml
install(TARGETS neriplayer RUNTIME DESTINATION lib/neriplayer)
install(DIRECTORY qml DESTINATION share/neriplayer)

