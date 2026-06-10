cmake_minimum_required(VERSION 3.18.1)
project(minihotfix)

add_library(minihotfix SHARED inline_hook.c)

find_library(log-lib log)
target_link_libraries(minihotfix ${log-lib})
