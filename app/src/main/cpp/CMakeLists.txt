cmake_minimum_required(VERSION 3.22.1)
project(audiostretch_native LANGUAGES CXX)

add_library(audiostretch_native SHARED native_audio_engine.cpp)

find_package(oboe REQUIRED CONFIG)
find_library(log-lib log)

target_link_libraries(audiostretch_native oboe::oboe ${log-lib})
