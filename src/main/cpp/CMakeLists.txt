cmake_minimum_required(VERSION 3.18.1)

project("bondbunny")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add compiler flags for optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -O3")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra -O3")

# Find required packages
find_library(log-lib log)
find_library(android-lib android)

# Native SRTLA library source files
add_library(
    srtla_native
    SHARED
    srtla_jni.cpp
    srtla_core.cpp
    srtla_connection.cpp
    srtla_protocol.cpp
    srtla_ip_manager.cpp
)

# Include directories
target_include_directories(
    srtla_native
    PRIVATE
    include
)

# Link libraries
target_link_libraries(
    srtla_native
    ${log-lib}
    ${android-lib}
)
