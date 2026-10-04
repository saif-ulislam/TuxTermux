cmake_minimum_required(VERSION 3.22.1)
project(tuxterm C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# libvterm is vendored upstream; it has no build system of its own to embed.
add_library(tuxterm SHARED
    tux_pty.c
    tux_vterm.c
    libvterm/encoding.c
    libvterm/keyboard.c
    libvterm/mouse.c
    libvterm/parser.c
    libvterm/pen.c
    libvterm/screen.c
    libvterm/state.c
    libvterm/unicode.c
    libvterm/vterm.c
)

target_include_directories(tuxterm PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/include
)

find_library(log-lib log)
target_link_libraries(tuxterm ${log-lib})
