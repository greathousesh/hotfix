cmake_minimum_required(VERSION 3.18.1)
project(native_bug)

add_library(native_bug SHARED native_bug.c)

# 关键：导出符号 compute_add，便于 dlsym 定位、内联 hook 改写
set_target_properties(native_bug PROPERTIES LINK_FLAGS "-Wl,--export-dynamic")

find_library(log-lib log)
target_link_libraries(native_bug ${log-lib})
